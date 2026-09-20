package com.metricol.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * Lo que cambia de un ambiente a otro vive en el perfil de cada ambiente y NO
 * en application.properties.
 *
 * <p>Un default en application.properties vale para todos: un secreto de
 * desarrollo o una llave de un ambiente se colarian en los demás. Estas pruebas
 * leen los archivos tal cual (sin levantar la aplicación) y comprueban dos
 * cosas: que ninguna de esas claves vuelva a application.properties, y que cada
 * perfil declare las suyas, con los valores que tenía producción antes de
 * separarlas.
 */
class ConfiguracionPorAmbienteTest {

    /** Lo que es de cada ambiente: si aparece en application.properties, se coló. */
    private static final List<String> DE_CADA_AMBIENTE = List.of(
            "spring.jpa.properties.hibernate.dialect",
            "jwt.secret", "jwt.expiration-days",
            "app.cors.allowed-origins", "app.base-url", "app.web-url",
            "app.ops.api-key",
            "app.demo.email", "app.demo.password", "app.demo.organization",
            "app.mail.from", "spring.mail.host", "spring.mail.port", "spring.mail.username", "spring.mail.password",
            "google.oauth.client-id",
            "cloudflare.r2.endpoint", "cloudflare.r2.access-key", "cloudflare.r2.secret-key", "cloudflare.r2.bucket",
            "cloudflare.r2.public-url",
            "uploadpost.api-key", "uploadpost.base-url",
            "openai.api-key", "openai.model", "openai.image-model", "openai.image-input-fidelity",
            "openai.director-model", "openai.pricing.input-usd-per-million", "openai.image-pricing.output-usd-per-million",
            "app.stripe.secret-key", "app.stripe.webhook-secret");

    /** Las variables que qa y prod exigen: sin ellas el arranque falla, a propósito. */
    private static final Map<String, Object> OBLIGATORIAS = Map.ofEntries(
            Map.entry("SPRING_DATASOURCE_URL", "jdbc:postgresql://x/y"), Map.entry("DB_USER", "u"), Map.entry("DB_PASS", "p"),
            Map.entry("CORS_ALLOWED_ORIGINS", "https://picale.click"), Map.entry("APP_BASE_URL", "https://api.picale.click"),
            Map.entry("JWT_SECRET", "un-secreto-largo-de-prueba-de-32-bytes!!"),
            Map.entry("CLOUDFLARE_R2_ENDPOINT", "https://r2"), Map.entry("CLOUDFLARE_R2_ACCESS_KEY_ID", "a"),
            Map.entry("CLOUDFLARE_R2_SECRET_ACCESS_KEY", "s"), Map.entry("CLOUDFLARE_R2_BUCKET_NAME", "b"),
            Map.entry("CLOUDFLARE_R2_PUBLIC_URL", "https://cdn"));

    /** El ambiente tal como lo vería la aplicación: variables, luego el perfil, luego application.properties. */
    private static StandardEnvironment entorno(String perfil, Map<String, Object> variables) throws IOException {
        StandardEnvironment env = new StandardEnvironment();
        // Sin las variables reales de esta máquina: la prueba no puede depender de quién la corre.
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        for (PropertySource<?> p : new PropertiesPropertySourceLoader().load("base", new ClassPathResource("application.properties"))) {
            env.getPropertySources().addLast(p);
        }
        for (PropertySource<?> p : new YamlPropertySourceLoader().load(perfil, new ClassPathResource("application-" + perfil + ".yml"))) {
            env.getPropertySources().addFirst(p);
        }
        env.getPropertySources().addFirst(new MapPropertySource("variables", variables));
        return env;
    }

    private static Map<String, Object> con(Map<String, Object> base, String clave, Object valor) {
        java.util.HashMap<String, Object> m = new java.util.HashMap<>(base);
        m.put(clave, valor);
        return m;
    }

    @Test
    @DisplayName("application.properties ya no trae nada que sea de un solo ambiente")
    void nadaDeUnAmbienteEnLasPropiedadesComunes() throws IOException {
        var comunes = new PropertiesPropertySourceLoader().load("base", new ClassPathResource("application.properties")).get(0);
        for (String clave : DE_CADA_AMBIENTE) {
            assertThat(comunes.containsProperty(clave)).as(clave + " es de cada ambiente: va en su perfil").isFalse();
        }
    }

    @Test
    @DisplayName("cada perfil declara todo lo suyo")
    void cadaPerfilDeclaraLoSuyo() throws IOException {
        for (String perfil : List.of("dev", "qa", "prod")) {
            StandardEnvironment env = entorno(perfil, OBLIGATORIAS);
            for (String clave : DE_CADA_AMBIENTE) {
                assertThat(env.containsProperty(clave)).as(perfil + " debe declarar " + clave).isTrue();
                assertThat(env.getProperty(clave)).as(perfil + ": " + clave + " debe resolverse").isNotNull();
            }
        }
    }

    @Test
    @DisplayName("prod y qa no arrancan sin lo obligatorio; dev arranca con defaults")
    void lasObligatoriasSonObligatorias() throws IOException {
        for (String perfil : List.of("qa", "prod")) {
            for (String falta : List.of("JWT_SECRET", "CORS_ALLOWED_ORIGINS", "APP_BASE_URL", "CLOUDFLARE_R2_BUCKET_NAME")) {
                java.util.HashMap<String, Object> sin = new java.util.HashMap<>(OBLIGATORIAS);
                sin.remove(falta);
                StandardEnvironment env = entorno(perfil, sin);
                String clave = switch (falta) {
                    case "JWT_SECRET" -> "jwt.secret";
                    case "CORS_ALLOWED_ORIGINS" -> "app.cors.allowed-origins";
                    case "APP_BASE_URL" -> "app.base-url";
                    default -> "cloudflare.r2.bucket";
                };
                assertThatThrownBy(() -> env.getProperty(clave)).as(perfil + " sin " + falta)
                        .isInstanceOf(IllegalArgumentException.class);
            }
        }

        StandardEnvironment dev = entorno("dev", Map.of());
        for (String clave : DE_CADA_AMBIENTE) {
            assertThat(dev.getProperty(clave)).as("dev resuelve " + clave + " sin ninguna variable").isNotNull();
        }
    }

    @Test
    @DisplayName("desarrollo apunta a lo local; producción, a lo real")
    void cadaAmbienteApuntaAloSuyo() throws IOException {
        StandardEnvironment dev = entorno("dev", Map.of());
        assertThat(dev.getProperty("app.cors.allowed-origins")).contains("localhost").contains("ngrok");
        assertThat(dev.getProperty("app.base-url")).isEqualTo("http://localhost:5050");
        assertThat(dev.getProperty("app.web-url")).isEqualTo("http://localhost:4200");
        assertThat(dev.getProperty("jwt.secret")).isNotBlank();

        StandardEnvironment prod = entorno("prod", OBLIGATORIAS);
        assertThat(prod.getProperty("app.web-url")).isEqualTo("https://picale.click");
        assertThat(prod.getProperty("app.cors.allowed-origins")).isEqualTo("https://picale.click").doesNotContain("localhost");
        assertThat(prod.getProperty("jwt.secret")).isEqualTo("un-secreto-largo-de-prueba-de-32-bytes!!");
    }

    @Test
    @DisplayName("las llaves de los proveedores nacen vacías en todos los ambientes: nada secreto en el repositorio")
    void sinSecretosEscritos() throws IOException {
        for (String perfil : List.of("dev", "qa", "prod")) {
            StandardEnvironment env = entorno(perfil, OBLIGATORIAS.entrySet().stream()
                    .filter(e -> !e.getKey().equals("JWT_SECRET")).collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
            for (String clave : List.of("openai.api-key", "uploadpost.api-key", "spring.mail.password", "app.ops.api-key",
                    "app.demo.password", "google.oauth.client-id", "app.stripe.secret-key", "app.stripe.webhook-secret")) {
                assertThat(env.getProperty(clave)).as(perfil + ": " + clave).isEmpty();
            }
        }
    }

    @Test
    @DisplayName("OpenAI: producción conserva los modelos y precios verificados; dev y qa los de siempre; y una variable manda")
    void openAiPorAmbiente() throws IOException {
        StandardEnvironment prod = entorno("prod", OBLIGATORIAS);
        assertThat(prod.getProperty("openai.image-model")).isEqualTo("gpt-image-2");
        assertThat(prod.getProperty("openai.image-input-fidelity")).isEmpty();
        assertThat(prod.getProperty("openai.image-pricing.input-usd-per-million")).isEqualTo("8.00");
        assertThat(prod.getProperty("openai.image-pricing.output-usd-per-million")).isEqualTo("30.00");
        assertThat(prod.getProperty("openai.director-model")).isEqualTo("gpt-5-mini");
        assertThat(prod.getProperty("openai.director-pricing.input-usd-per-million")).isEqualTo("0.25");
        assertThat(prod.getProperty("openai.director-pricing.output-usd-per-million")).isEqualTo("2.00");

        for (String perfil : List.of("dev", "qa")) {
            StandardEnvironment env = entorno(perfil, OBLIGATORIAS);
            assertThat(env.getProperty("openai.image-model")).as(perfil).isEqualTo("gpt-image-1.5");
            assertThat(env.getProperty("openai.image-input-fidelity")).as(perfil).isEqualTo("high");
            assertThat(env.getProperty("openai.image-pricing.input-usd-per-million")).as(perfil).isEqualTo("0");
        }

        assertThat(entorno("prod", con(OBLIGATORIAS, "OPENAI_IMAGE_MODEL", "otro-modelo")).getProperty("openai.image-model"))
                .as("el .env manda sobre el default").isEqualTo("otro-modelo");
    }

    @Test
    @DisplayName("el correo trae los valores de siempre, y el TLS obligatorio sigue siendo común")
    void correo() throws IOException {
        for (String perfil : List.of("dev", "qa", "prod")) {
            StandardEnvironment env = entorno(perfil, OBLIGATORIAS);
            assertThat(env.getProperty("spring.mail.host")).as(perfil).isEqualTo("smtp.resend.com");
            assertThat(env.getProperty("spring.mail.port")).as(perfil).isEqualTo("587");
            assertThat(env.getProperty("spring.mail.username")).as(perfil).isEqualTo("resend");
            assertThat(env.getProperty("app.mail.from")).as(perfil).isEqualTo("notificaciones@picale.rodtech.cloud");
            assertThat(env.getProperty("spring.mail.properties.mail.smtp.starttls.required")).as(perfil).isEqualTo("true");
        }
    }
}
