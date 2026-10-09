package com.metricol.api.controller;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Qué base está usando el servidor, dicho por él mismo.
 *
 * <p>Existe por el 9 oct 2026: un despliegue a mano levantó la API contra otra
 * base y estuvo seis horas así. Respondía 200, la salud estaba en verde y el
 * commit era el correcto — el único síntoma era que a la gente le aparecían
 * datos que no eran suyos. Comprobar un despliegue pasó a ser "responde, con
 * este commit y contra esta base".
 */
class VersionControllerTest {

    @Test
    @DisplayName("del JDBC sale el nombre de la base, y nada más")
    void sacaElNombre() {
        assertThat(VersionController.nombreDeLaBase(
                "jdbc:postgresql://cloud-sql-proxy:5432/picale_dev")).isEqualTo("picale_dev");
        assertThat(VersionController.nombreDeLaBase(
                "jdbc:postgresql://cloud-sql-proxy:5432/metricol")).isEqualTo("metricol");
    }

    @Test
    @DisplayName("los parámetros de la URL no se cuelan en el nombre")
    void sinParametros() {
        assertThat(VersionController.nombreDeLaBase(
                "jdbc:postgresql://host:5432/picale_dev?sslmode=require&user=metricol"))
                .isEqualTo("picale_dev");
    }

    @Test
    @DisplayName("NUNCA se publica el host, el usuario ni la contraseña")
    void nadaDeSecretos() {
        String nombre = VersionController.nombreDeLaBase(
                "jdbc:postgresql://10.0.0.5:5432/picale_dev?user=metricol&password=supersecreta");

        assertThat(nombre).isEqualTo("picale_dev");
        assertThat(nombre).doesNotContain("supersecreta").doesNotContain("10.0.0.5")
                .doesNotContain("metricol");
    }

    @Test
    @DisplayName("una URL vacía o rara no tumba el arranque: se dice que no se sabe")
    void urlRara() {
        assertThat(VersionController.nombreDeLaBase(null)).isEqualTo("desconocida");
        assertThat(VersionController.nombreDeLaBase("")).isEqualTo("desconocida");
        assertThat(VersionController.nombreDeLaBase("jdbc:postgresql://host:5432/"))
                .isEqualTo("desconocida");
        assertThat(VersionController.nombreDeLaBase("algo-sin-forma")).isEqualTo("desconocida");
    }
}
