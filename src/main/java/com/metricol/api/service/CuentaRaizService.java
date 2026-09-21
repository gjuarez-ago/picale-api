package com.metricol.api.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.metricol.api.entity.Organization;
import com.metricol.api.entity.User;
import com.metricol.api.enums.ObjetivoRedes;
import com.metricol.api.exception.ResourceNotFoundException;
import com.metricol.api.models.request.BrandRequest;
import com.metricol.api.repository.OrganizationRepository;
import com.metricol.api.repository.UserRepository;

/**
 * Deja una cuenta ya creada como la CUENTA RAÍZ: la organización de la casa, sin límites ni
 * vigencia, con la marca de Pícale llena.
 *
 * <p>Lo llama solo el arranque ({@link com.metricol.api.config.DemoAccountInitializer}) y solo
 * cuando el servidor lo pide con {@code DEMO_ACCOUNT_ROOT=true}. No hay endpoint: que una cuenta
 * quede sin límites no puede ser algo que cualquiera con sesión pueda pedir.
 *
 * <p>La marca es la de Pícale, no un texto de relleno: el contenido que esta cuenta genera es el
 * de la propia empresa, y la IA solo escribe como Pícale si sabe qué es Pícale. Es editable desde
 * «Mi marca» como la de cualquier espacio; aquí solo se siembra.
 */
@Service
public class CuentaRaizService {

    private final UserRepository usuarios;
    private final OrganizationRepository organizaciones;
    private final BrandService marca;

    public CuentaRaizService(UserRepository usuarios, OrganizationRepository organizaciones, BrandService marca) {
        this.usuarios = usuarios;
        this.organizaciones = organizaciones;
        this.marca = marca;
    }

    /**
     * @param correo             la cuenta, ya registrada
     * @param nombreOrganizacion cómo se llama su organización; si viene vacío se deja el que tenga
     */
    @Transactional
    public void convertir(String correo, String nombreOrganizacion) {
        User usuario = usuarios.findByEmail(correo)
                .orElseThrow(() -> new ResourceNotFoundException("No existe la cuenta " + correo));

        Organization organizacion = organizaciones.findById(usuario.getWorkspace().getOrganization().getId())
                .orElseThrow(() -> new ResourceNotFoundException("La cuenta no tiene organización."));
        organizacion.setSinLimites(true);
        if (nombreOrganizacion != null && !nombreOrganizacion.isBlank()) {
            organizacion.setName(nombreOrganizacion.strip());
        }
        organizaciones.save(organizacion);

        marca.guardar(usuario, marcaDePicale());
    }

    /**
     * Qué es Pícale, con las palabras que usa la propia plataforma. Sin precios ni cifras: cambian,
     * y una marca con un precio viejo escribe publicaciones con un precio viejo. La ciudad y el
     * WhatsApp se dejan vacíos a propósito: no hay un dato real que poner y la IA no debe inventarlo.
     */
    static BrandRequest marcaDePicale() {
        BrandRequest m = new BrandRequest();
        m.setGiro("Software para redes sociales con IA");
        m.setDescripcion("Pícale es la plataforma para crear, programar y publicar en todas tus redes sociales desde un solo "
                + "lugar. Con inteligencia artificial redacta los textos y diseña las imágenes de tus campañas, y publica en "
                + "Facebook, Instagram, TikTok, YouTube y LinkedIn, para que cualquier negocio se vea profesional sin complicarse.");
        m.setObjetivo(ObjetivoRedes.MAS_CLIENTES);
        m.setQueVende("Una suscripción mensual por negocio: publicación en todas las redes, textos e imágenes con IA (con "
                + "créditos incluidos) y paquetes de imágenes extra. Con prueba gratis para empezar.");
        m.setPublico("Dueños de negocios pequeños y medianos y emprendedores en México, y agencias o community managers que "
                + "llevan las redes de varios clientes y no tienen tiempo ni equipo de diseño.");
        m.setTono(List.of("CERCANO", "CONFIABLE", "DIRECTO"));
        m.setEvitar("Tecnicismos, prometer resultados garantizados o volverse viral, y compararse con otras marcas por su nombre.");
        m.setWeb("https://picale.rodtech.cloud");
        return m;
    }
}
