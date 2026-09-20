package com.metricol.api.models.request;

import java.util.List;

import com.metricol.api.enums.ObjetivoRedes;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * Lo que se cuenta de la marca. Los cuatro datos del negocio (giro, ciudad,
 * descripción, objetivo) siguen la regla de siempre: nulo = «no lo toques».
 * Los de marca REEMPLAZAN el perfil completo: la pantalla siempre manda todo, y
 * vaciar un campo es mandarlo vacío.
 */
@Getter
@Setter
public class BrandRequest {

    @Size(max = 120)
    private String giro;

    @Size(max = 120)
    private String ciudad;

    @Size(max = 500)
    private String descripcion;

    private ObjetivoRedes objetivo;

    @Size(max = 400)
    private String queVende;

    @Size(max = 300)
    private String publico;

    /** Códigos de TonoDeMarca, hasta tres. */
    @Size(max = 8)
    private List<@Size(max = 30) String> tono;

    @Size(max = 200)
    private String evitar;

    @Size(max = 40)
    private String whatsapp;

    @Size(max = 200)
    private String web;

    @Size(max = 200)
    private String direccion;
}
