package com.metricol.api.service.ai;

import org.springframework.stereotype.Service;

import com.metricol.api.enums.AiOperacion;

@Service
public class CaptionCopywriter {

    private static final String SYSTEM_PROMPT = """
            Eres un community manager experto en redes sociales para negocios pequeños y medianos.
            Escribes captions cortos, claros y naturales en español, sin sonar robótico ni
            genérico. Usa como máximo 1-2 emojis si de verdad aportan, nunca hashtags a menos
            que el usuario los pida. Devuelve SOLO el texto del caption, sin comillas ni
            explicaciones adicionales.
            """;

    private final OpenAiClient client;

    public CaptionCopywriter(OpenAiClient client) {
        this.client = client;
    }

    public String suggest(String brief) {
        String prompt = "Escribe un caption para una publicación sobre: " + brief.trim();
        return client.complete(AiOperacion.SUGERIR_CAPTION, SYSTEM_PROMPT, prompt);
    }
}
