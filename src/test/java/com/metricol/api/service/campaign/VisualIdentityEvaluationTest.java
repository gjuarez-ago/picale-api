package com.metricol.api.service.campaign;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import com.metricol.api.config.OpenAiProperties;
import com.metricol.api.entity.*;
import com.metricol.api.enums.*;
import com.metricol.api.models.request.CampaignImageRequest;
import com.metricol.api.repository.*;
import com.metricol.api.service.ai.*;
import com.metricol.api.service.billing.CreditService;
import com.metricol.api.service.storage.*;

/** Evaluación REAL opt-in: 8 imágenes iniciales y 2 adicionales autorizadas; sin BD, R2 ni publicaciones. */
@EnabledIfEnvironmentVariable(named = "PICALE_VISUAL_EVAL", matches = "true")
@org.junit.jupiter.api.TestMethodOrder(org.junit.jupiter.api.MethodOrderer.OrderAnnotation.class)
class VisualIdentityEvaluationTest {
    final ObjectMapper json = new ObjectMapper();
    final Path directory = Path.of("target", "visual-identity-eval");
    final UUID workspaceId = UUID.nameUUIDFromBytes("picale-visual-eval".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    final UUID chispaId = UUID.nameUUIDFromBytes("chispa".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    final List<Usage> usage = new CopyOnWriteArrayList<>();
    record Usage(String model, int inputTokens, int outputTokens) {}
    ObjectNode manifest;
    OpenAiProperties props;
    OpenAiImageClient images;
    CampaignImageService service;
    Workspace workspace;
    String label;
    List<String> outputFiles = new ArrayList<>();

    @Test @org.junit.jupiter.api.Order(1) void compare() throws Exception {
        assertThat(System.getenv("OPENAI_API_KEY")).as("La evaluación necesita una llave en el entorno, nunca en argumentos o resultados").isNotBlank();
        Files.createDirectories(directory);
        Path manifestFile = directory.resolve("manifest.json");
        manifest = Files.exists(manifestFile) ? (ObjectNode) json.readTree(Files.readString(manifestFile)) : json.createObjectNode();
        if (!manifest.has("steps")) {
            manifest.put("createdAt", Instant.now().toString()).put("model", "gpt-image-2").put("maximumImageRequests", 8);
            manifest.put("pricingSource", "https://developers.openai.com/api/docs/pricing");
            manifest.put("costNote", "Estimación conservadora USD: todo input a $4/M + output a $15/M. No es factura; no incluye texto/revisión ni impuestos.");
            manifest.putArray("steps");
        }
        props = new OpenAiProperties(); props.setApiKey(System.getenv("OPENAI_API_KEY")); props.setImageModel("gpt-image-2");
        images = new OpenAiImageClient(props);
        try {
            setupService();
            step("marca-antes-medium", "medium", false, true, false);
            step("marca-integrada-medium", "medium", false, false, false);
            step("marca-integrada-high", "high", false, false, false);
            step("chispa-antes-medium", "medium", true, true, false);
            step("chispa-referencia-medium", "medium", true, false, false);
            step("chispa-referencia-high", "high", true, false, false);
            step("carrusel-compartido-medium", "medium", true, false, true);
        } finally { if (service != null) service.cerrar(); writeReport(); }
        assertThat(manifest.path("imageRequestsStarted").asInt()).isLessThanOrEqualTo(manifest.path("maximumImageRequests").asInt());
    }

    @Test @org.junit.jupiter.api.Order(3)
    @EnabledIfEnvironmentVariable(named = "PICALE_VISUAL_FOLLOWUP", matches = "true")
    void verifyCorrectedLogoAndSelectedPiece() throws Exception {
        manifest = (ObjectNode) json.readTree(Files.readString(directory.resolve("manifest.json")));
        assertThat(manifest.path("imageRequestsStarted").asInt()).isLessThanOrEqualTo(10);
        manifest.put("maximumImageRequests", 10).put("additionalAuthorization", "Dos imágenes adicionales autorizadas explícitamente en la conversación; ninguna publicación.");
        props = new OpenAiProperties(); props.setApiKey(System.getenv("OPENAI_API_KEY")); props.setImageModel("gpt-image-2");
        images = new OpenAiImageClient(props);
        try {
            setupService();
            step("chispa-logo-unico-medium", "medium", true, false, false);
            step("chispa-ajuste-seleccionado-medium", "medium", false, false, false, "chispa-referencia-medium-3.jpg",
                    "Retira SOLO el logotipo plano adicional de la esquina inferior izquierda. Conserva el logotipo tridimensional central, el personaje, los textos, la composición, el fondo y todo lo demás sin cambios.");
        } finally { if (service != null) service.cerrar(); writeReport(); }
    }

    /** Revisa SOLO archivos existentes; no vuelve a llamar al generador ni altera el límite autorizado. */
    @Test @org.junit.jupiter.api.Order(2) void reviewSavedOutputs() throws Exception {
        manifest = (ObjectNode) json.readTree(Files.readString(directory.resolve("manifest.json")));
        props = new OpenAiProperties(); props.setApiKey(System.getenv("OPENAI_API_KEY"));
        props.setModel(System.getenv().getOrDefault("OPENAI_API_MODEL", "gpt-5-mini"));
        assertThat(props.isConfigured()).isTrue();
        var recorder = mock(AiUsageRecorder.class);
        var reviewer = new VisualQualityReview(new OpenAiClient(props, recorder));
        byte[] logo = Files.readAllBytes(Path.of("..", "metricol_web", "public", "logotipo-navbar.png"));
        byte[] chispa = Files.readAllBytes(Path.of("..", "metricol_web", "public", "images", "agents", "chispa.png"));
        for (var node : manifest.path("steps")) {
            ObjectNode step = (ObjectNode) node;
            if (!"COMPLETED".equals(step.path("status").asText()) || step.has("visualReview")) continue;
            List<byte[]> files = new ArrayList<>();
            for (var file : step.path("files")) files.add(Files.readAllBytes(directory.resolve(file.asText())));
            List<OpenAiImageClient.Referencia> identity = new ArrayList<>();
            identity.add(new OpenAiImageClient.Referencia(logo, "logo-original.png", "image/png"));
            if (step.path("name").asText().startsWith("chispa-") || step.path("name").asText().startsWith("carrusel-"))
                identity.add(new OpenAiImageClient.Referencia(chispa, "chispa-original.png", "image/png"));
            long started = System.nanoTime();
            String specification = Files.readString(directory.resolve(step.path("name").asText() + "-prompt.txt"));
            if (step.path("name").asText().contains("-antes-")) specification +=
                    "\nFinal composition exception: one ORIGINAL PICALE logo is intentionally composed at top left after generation. This is authorized, not a defect.";
            if (step.path("name").asText().startsWith("carrusel-")) specification +=
                    "\nAuthorized per-slide exact text: slide 1 = Ordena tus ideas; slide 2 = Dales vida con Chispa. The saved generation prompt above is for slide 1 only.";
            var review = reviewer.review(files, identity, specification + "\nReference 1 is PICALE's original logo; reference 2, if supplied, is Chispa. No extra standalone symbol when the wordmark is already present. Check unauthorized extra copy. For a carousel, per-slide copy may differ intentionally.");
            step.set("visualReview", json.valueToTree(review));
            step.put("visionMilliseconds", (System.nanoTime() - started) / 1_000_000);
            writeReport();
        }
    }

    private void setupService() throws Exception {
        Path web = Path.of("..", "metricol_web", "public");
        byte[] logo = Files.readAllBytes(web.resolve("logotipo-navbar.png"));
        byte[] chispa = Files.readAllBytes(web.resolve("images/agents/chispa.png"));
        String logoUrl = "https://eval.invalid/logo.png", chispaUrl = "https://eval.invalid/chispa.png";
        var assets = mock(MediaAssetRepository.class);
        MediaAsset logoAsset = asset(UUID.randomUUID(), logoUrl, "logo"), chispaAsset = asset(chispaId, chispaUrl, "chispa");
        Map<UUID, MediaAsset> localAssets = new LinkedHashMap<>();
        localAssets.put(logoAsset.getId(), logoAsset); localAssets.put(chispaId, chispaAsset);
        Map<String, byte[]> localBytes = new LinkedHashMap<>(); localBytes.put("logo", logo); localBytes.put("chispa", chispa);
        try (var files = Files.list(directory)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".jpg")).toList()) {
                String name = file.getFileName().toString();
                UUID id = UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                localAssets.put(id, asset(id, "https://eval.invalid/" + name, name));
                localBytes.put(name, Files.readAllBytes(file));
            }
        }
        when(assets.findById(any())).thenAnswer(i -> Optional.ofNullable(localAssets.get(i.getArgument(0))));
        when(assets.findByUrlIn(anyCollection())).thenAnswer(i -> {
            Collection<String> urls = i.getArgument(0);
            return localAssets.values().stream().filter(a -> urls.contains(a.getUrl())).toList();
        });
        when(assets.saveAll(anyList())).thenAnswer(i -> {
            List<MediaAsset> rows = i.getArgument(0); rows.forEach(a -> a.setId(UUID.randomUUID())); return rows;
        });
        var storage = mock(R2StorageService.class);
        when(storage.descargar(anyString(), any(Path.class))).thenAnswer(i -> {
            Files.write(i.getArgument(1), localBytes.get(i.getArgument(0))); return true;
        });
        AtomicInteger counter = new AtomicInteger();
        when(storage.claveNueva(any(), anyString(), anyString())).thenAnswer(i -> label + "-" + counter.incrementAndGet() + ".jpg");
        when(storage.subirBytes(anyString(), any(byte[].class), anyString())).thenAnswer(i -> {
            String name = i.getArgument(0); byte[] bytes = i.getArgument(1);
            Files.write(directory.resolve(name), bytes); outputFiles.add(name); return "https://eval.invalid/" + name;
        });
        var usageRecorder = mock(AiUsageRecorder.class);
        doAnswer(i -> { usage.add(new Usage(i.getArgument(0), i.getArgument(1), i.getArgument(2))); return null; })
                .when(usageRecorder).registrarImagen(anyString(), anyInt(), anyInt());
        var texts = mock(OpenAiClient.class);
        when(texts.completeJson(any(), anyString(), anyString())).thenReturn("{\"headline\":\"Tu marketing, contigo\",\"supportingCopy\":\"\",\"caption\":\"Picale acompaña tu negocio\"}");
        // El texto es fijo en ambos caminos para aislar la diferencia visual. La revisión humana decide aprobación.
        var director = mock(ArtDirector.class);
        when(director.disponible()).thenReturn(true);
        when(director.dirigir(any())).thenReturn(Optional.of(new ArtDirector.Brief("full_bleed", 0,
                "A premium tactile editorial scene in electric blue and turquoise, thoughtful negative space, polished studio light.",
                "Tu marketing, contigo", "", "", "Picale acompaña tu negocio", Map.of())));
        var quota = mock(AiQuotaGuard.class); when(quota.exigirCupoImagenes(anyInt())).thenReturn(Integer.MAX_VALUE);
        var credits = mock(CreditService.class); when(credits.consumirGeneracion(any(), anyString())).thenReturn(Integer.MAX_VALUE);
        service = new CampaignImageService(images, texts, storage, assets, mock(PostRepository.class), mock(StorageQuotaService.class), quota, usageRecorder, director, credits);
        workspace = Workspace.builder().id(workspaceId).name("PICALE").giro("Marketing").descripcion("Equipo de marketing para negocios").logoUrl(logoUrl).build();
        var identity = new VisualIdentity(List.of("#08245E", "#0066FF", "#20DDF2"),
                "Editorial tridimensional, cercano y limpio; jerarquía clara y pocos elementos, sin apariencia de stock.",
                List.of(), List.of(), List.of(chispaId), "Sin marcos genéricos ni carteles de plantilla",
                "Letras PICALE y símbolo reconocible. Chispa azul/turquesa, ojos grandes y bolsa azul marino con cierre coral.",
                "Posición, expresión, pose y luz", VisualIdentity.LogoTreatment.INTEGRATED, VisualIdentity.LogoBackground.PRESERVE,
                false, 1, Instant.now().toString(), UUID.randomUUID());
        workspace.setBrandProfile(BrandProfile.VACIO.withVisualIdentity(identity));
    }
    private MediaAsset asset(UUID id, String url, String key) {
        return MediaAsset.builder().id(id).tenantId(workspaceId.toString()).url(url).storageKey(key).contentType(url.endsWith(".jpg") ? "image/jpeg" : "image/png")
                .type(MediaType.IMAGE).status(MediaAssetStatus.READY).fileName(key + ".png").sizeBytes(1L).build();
    }

    private void step(String name, String quality, boolean withChispa, boolean baseline, boolean carousel) throws Exception {
        step(name, quality, withChispa, baseline, carousel, null, null);
    }
    private void step(String name, String quality, boolean withChispa, boolean baseline, boolean carousel, String previousFile, String adjustment) throws Exception {
        for (var previous : manifest.path("steps")) if (name.equals(previous.path("name").asText())) {
            if ("COMPLETED".equals(previous.path("status").asText())) return;
            throw new IllegalStateException("Hay una solicitud previa sin resultado confirmado. No se repetirá automáticamente: " + name);
        }
        int requests = carousel ? 2 : 1;
        int started = manifest.path("imageRequestsStarted").asInt();
        if (started + requests > manifest.path("maximumImageRequests").asInt()) throw new IllegalStateException("Se alcanzó el máximo autorizado de imágenes.");
        ObjectNode step = ((ArrayNode) manifest.path("steps")).addObject().put("name", name).put("quality", quality).put("status", "STARTED");
        manifest.put("imageRequestsStarted", started + requests);
        json.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("manifest.json").toFile(), manifest);
        label = name; props.setImageQuality(quality); usage.clear(); outputFiles = new ArrayList<>();
        long start = System.nanoTime();
        try {
            if (baseline) {
                var data = new PromptDeImagen.Datos(Lienzo.CUADRADO, PromptDeImagen.Layout.PHOTO_BOTTOM_BAND, "PICALE",
                        withChispa ? "A friendly blue star mascot helps organize a marketing campaign in a professional advertisement." : "A professional marketing agency advertisement with blue objects.",
                        "Tu marketing, contigo", "", "", List.of("#08245E", "#0066FF"), 0, SelloDeLogo.Posicion.TOP_LEFT);
                String prompt = PromptDeImagen.armar(data);
                Files.writeString(directory.resolve(name + "-prompt.txt"), prompt);
                var output = images.generar(prompt, "1024x1024");
                usage.add(new Usage(output.modelo(), output.tokensEntrada(), output.tokensSalida()));
                byte[] logo = Files.readAllBytes(Path.of("..", "metricol_web", "public", "logotipo-navbar.png"));
                byte[] composed = SelloDeLogo.colocar(output.imagen(), logo, SelloDeLogo.Posicion.TOP_LEFT, false).imagen();
                String file = name + ".jpg"; Files.write(directory.resolve(file), composed); outputFiles.add(file);
            } else {
                var request = new CampaignImageRequest(null, new CampaignImageRequest.Format(carousel ? "carousel" : "post", null, null),
                        carousel ? List.of("https://eval.invalid/chispa.png", "https://eval.invalid/chispa.png") : List.of(), null,
                        withChispa ? "Chispa, el personaje de referencia, organiza ideas de marketing sobre una mesa con tarjetas abstractas; pose nueva, misma identidad. Logo integrado en un objeto de la escena."
                                : "Campaña de Picale: organiza tu marketing con claridad. Integra el logo original como un relieve natural en un objeto de escritorio, con perspectiva y luz coherente.",
                        "Reconocimiento de marca", null, null, null, false, List.of("LINKEDIN"),
                        new CampaignImageRequest.Creative(VisualIdentity.LogoTreatment.INTEGRATED,
                                previousFile == null ? null : UUID.nameUUIDFromBytes(previousFile.getBytes(java.nio.charset.StandardCharsets.UTF_8)), adjustment, withChispa,
                                carousel ? null : "Tu marketing, contigo", carousel ? List.of("Ordena tus ideas", "Dales vida con Chispa") : null));
                var prepared = service.preparar(User.builder().workspace(workspace).build(), request);
                var result = service.ejecutar(prepared, CampaignImageService.Progreso.NINGUNO);
                Files.writeString(directory.resolve(name + "-prompt.txt"), result.prompt());
                assertThat(result.variantes()).allSatisfy(v -> assertThat(v.causa()).isNull());
            }
            step.put("status", "COMPLETED");
        } catch (Exception | AssertionError error) {
            step.put("status", "FAILED").put("error", error.getClass().getSimpleName()); throw error;
        } finally {
            step.put("milliseconds", (System.nanoTime() - start) / 1_000_000);
            step.set("files", json.valueToTree(outputFiles)); step.set("usage", json.valueToTree(usage));
            double estimate = usage.stream().mapToDouble(u -> (u.inputTokens() * 4.0 + u.outputTokens() * 15.0) / 1_000_000).sum();
            step.put("estimatedUpperUsd", estimate); step.put("humanApproval", "PENDING");
            writeReport();
        }
    }
    private void writeReport() throws Exception {
        json.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("manifest.json").toFile(), manifest);
        StringBuilder html = new StringBuilder("<!doctype html><meta charset='utf-8'><title>Evaluación visual Picale</title><style>body{font:16px system-ui;background:#f3f6fc;color:#102653;margin:32px}main{display:grid;grid-template-columns:repeat(auto-fit,minmax(280px,1fr));gap:20px}article{background:white;padding:20px;border-radius:18px}img{width:100%;border-radius:12px}small{display:block;margin:10px 0}textarea{width:95%;min-height:70px}</style><h1>Identidad visual · Picale y Chispa</h1><p>Comparación exploratoria. Texto y dirección fijados para aislar el cambio visual. No es una prueba estadística ni una aprobación humana.</p><p>Coste indicado: estimación conservadora de generación; pendiente cotejar con facturación. Nada se publica ni se guarda en datos reales.</p><main>");
        for (var step : manifest.path("steps")) {
            html.append("<article><h2>").append(step.path("name").asText()).append("</h2><small>")
                    .append(step.path("status").asText()).append(" · ").append(step.path("milliseconds").asLong() / 1000.0)
                    .append(" s · ≤ USD ").append(String.format(Locale.ROOT, "%.4f", step.path("estimatedUpperUsd").asDouble())).append("</small>");
            for (var file : step.path("files")) html.append("<a href='").append(file.asText()).append("'><img src='").append(file.asText()).append("'></a>");
            if (step.has("visualReview")) {
                html.append("<p>Revisión automática: ").append(step.path("visualReview").path("status").asText()).append("</p><ul>");
                for (var issue : step.path("visualReview").path("issues")) html.append("<li>").append(escape(issue.asText())).append("</li>");
                html.append("</ul><small>Orientativa; no sustituye tu aprobación.</small>");
            }
            html.append("<p>Revisar: identidad, letras, logo único, fondo, legibilidad y coherencia.</p><label>Aprobación humana <select data-cost='")
                    .append(step.path("estimatedUpperUsd").asDouble()).append("' data-count='").append(step.path("files").size())
                    .append("'><option>Pendiente</option><option>Aprobada</option><option>Necesita ajustes</option><option>Rechazada</option></select></label><p><textarea placeholder='Observaciones para Chispa'></textarea></p></article>");
        }
        Files.writeString(directory.resolve("index.html"), html.append("</main><p id='cost-result'>Sin aprobación humana todavía: no se calcula coste por pieza aprobada.</p><p>La selección de esta hoja es local y no modifica preferencias de marca. Coste exploratorio = generación total de la comparación / imágenes que apruebes; no es un precio de producción.</p><script>const selects=[...document.querySelectorAll('select')];for(const s of selects)s.addEventListener('change',()=>{const count=selects.reduce((n,x)=>n+(x.value==='Aprobada'?Number(x.dataset.count):0),0);const cost=selects.reduce((n,x)=>n+Number(x.dataset.cost),0);document.getElementById('cost-result').textContent=count?`${count} imágenes aprobadas. Coste exploratorio estimado por aprobada: ≤ USD ${(cost/count).toFixed(4)}`:'Sin aprobación humana todavía: no se calcula coste por pieza aprobada.'})</script>").toString());
    }
    private static String escape(String text) { return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); }
}
