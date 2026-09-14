package com.metricol.api.config;

import java.net.URI;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration
public class R2Config {

    @Bean
    public S3Client r2Client(R2Properties props) {
        // Sin credenciales configuradas, se arma un cliente "apuntando a nada" en
        // vez de tronar el arranque de la app: R2StorageService revisa
        // props.isConfigured() antes de usarlo, así que este cliente nunca llega
        // a hacer una petición real hasta que se definan las variables R2_*.
        String endpoint = props.isConfigured() ? props.getEndpoint() : "https://r2.local";
        String accessKey = props.isConfigured() ? props.getAccessKey() : "sin-configurar";
        String secretKey = props.isConfigured() ? props.getSecretKey() : "sin-configurar";

        // R2 es compatible con la API de S3; "auto" es la región que espera R2.
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of("auto"))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .build();
    }

    /**
     * Firma las URLs con las que la app sube directo a R2, sin pasar por aquí.
     *
     * <p>Es lo que quita a la API de en medio del camino de los bytes. Con la
     * subida por multipart, un video de 40 MB entraba a este servidor y salía
     * hacia R2: el doble de tráfico, un hilo de petición retenido todo el
     * rato, y —lo que de verdad importaba— un techo de 32 MiB por petición en
     * la plataforma donde esto corre, que rechazaba el archivo antes de que
     * Spring lo viera. Firmando la URL, el archivo va del teléfono a R2 y por
     * aquí solo pasan dos peticiones cortas: la que firma y la que confirma.
     *
     * <p>Se arma con las mismas credenciales que el cliente y con la misma
     * tolerancia a que falten: sin configurar apunta a un endpoint inventado y
     * nadie lo llama, porque {@code R2StorageService} revisa
     * {@code isConfigured()} antes.
     */
    @Bean
    public S3Presigner r2Presigner(R2Properties props) {
        String endpoint = props.isConfigured() ? props.getEndpoint() : "https://r2.local";
        String accessKey = props.isConfigured() ? props.getAccessKey() : "sin-configurar";
        String secretKey = props.isConfigured() ? props.getSecretKey() : "sin-configurar";

        return S3Presigner.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of("auto"))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .build();
    }
}
