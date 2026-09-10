package org.kindbox.service.configuracion;

import java.nio.file.Path;
import org.kindbox.core.io.RepositorioDatos;
import org.kindbox.core.modelo.ParametrosOperacion;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Piezas compartidas del servicio y politica de CORS.
 *
 * <p>El frontend del visualizador corre en otro origen durante el desarrollo, de modo que
 * la politica es deliberadamente abierta. Es una decision de entorno de curso: la
 * aplicacion no guarda credenciales ni datos personales, solo el escenario de operacion.</p>
 *
 * <p>{@link ParametrosOperacion} es un unico objeto vivo para todo el proceso. Ese es el
 * punto exacto en que se materializan las respuestas 6, 15 y 16 del cuestionario: los
 * extremos {@code PUT /api/parametros/*} lo modifican en caliente y cada iteracion de
 * planificacion captura una instantanea inmutable, de modo que el cambio se aplica a partir
 * de la siguiente iteracion y nunca a mitad de una.</p>
 */
@Configuration
public class ConfiguracionWeb implements WebMvcConfigurer {

    private final PropiedadesKindBox propiedades;

    public ConfiguracionWeb(PropiedadesKindBox propiedades) {
        this.propiedades = propiedades;
    }

    @Override
    public void addCorsMappings(CorsRegistry registro) {
        registro.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(false)
                .maxAge(3600);
    }

    /** Repositorio de datos apuntando al directorio configurado, "data" por defecto. */
    @Bean
    public RepositorioDatos repositorioDatos() {
        return new RepositorioDatos(Path.of(propiedades.getDirectorioDatos()));
    }

    /** Parametros de operacion vivos, compartidos por el servicio y por el motor. */
    @Bean
    public ParametrosOperacion parametrosOperacion() {
        return new ParametrosOperacion();
    }
}
