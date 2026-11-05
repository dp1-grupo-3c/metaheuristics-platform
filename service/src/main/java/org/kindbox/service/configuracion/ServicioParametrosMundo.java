package org.kindbox.service.configuracion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.kindbox.core.configuracion.ParametrosMundo;
import org.kindbox.core.configuracion.ParametrosMundoInvalidos;
import org.kindbox.service.error.ErrorDeDatos;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Version vigente de los {@link ParametrosMundo}, persistida en un archivo JSON.
 *
 * <p>El archivo es la fuente de verdad: al arrancar se lee y, si no existe, se crea con los
 * valores del enunciado. Cada cambio aceptado se escribe con un temporal y un movimiento
 * atomico, como el registro de clientes, y solo despues pasa a ser la version vigente; si la
 * escritura falla, la vigente no cambia. El archivo tambien puede editarse a mano con el
 * servidor en marcha y aplicarse con {@link #recargar()}.</p>
 *
 * <p>Cada corrida debe tomar {@link #vigente()} una sola vez al arrancar y conservarla: la
 * version permite registrar en su resultado con que parametros se obtuvo.</p>
 */
@Service
public class ServicioParametrosMundo {

    /** Parametros junto con su version, que crece con cada cambio aceptado. */
    public record Versionados(long version, ParametrosMundo parametros) {
    }

    private final Path archivo;
    private final ObjectMapper json;
    private volatile Versionados vigente;

    @Autowired
    public ServicioParametrosMundo(PropiedadesKindBox propiedades, ObjectMapper json) {
        this(Path.of(propiedades.getArchivoParametrosMundo()), json);
    }

    public ServicioParametrosMundo(Path archivo, ObjectMapper json) {
        this.archivo = archivo.toAbsolutePath();
        // El formato del archivo no debe depender de la configuracion del mapper inyectado, y
        // un campo mal escrito debe rechazarse en lugar de ignorarse en silencio.
        this.json = json.copy()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
        if (Files.exists(this.archivo)) {
            this.vigente = new Versionados(1, leer());
        } else {
            ParametrosMundo enunciado = ParametrosMundo.delEnunciado();
            escribir(enunciado);
            this.vigente = new Versionados(1, enunciado);
        }
    }

    // Solo con Spring: el nucleo es global y las pruebas que crean el servicio no deben tocarlo.
    @PostConstruct
    void aplicarAlArrancar() {
        vigente.parametros().aplicarAlNucleo();
    }

    /** Version vigente. Una corrida la toma al arrancar y no vuelve a consultarla. */
    public Versionados vigente() {
        return vigente;
    }

    /**
     * Reemplaza los parametros vigentes. Los parametros ya llegan validados, porque un
     * {@link ParametrosMundo} incoherente no puede construirse.
     */
    public synchronized Versionados actualizar(ParametrosMundo nuevos) {
        escribir(nuevos);
        vigente = new Versionados(vigente.version() + 1, nuevos);
        return vigente;
    }

    /** Vuelve a los valores del enunciado. */
    public Versionados restablecer() {
        return actualizar(ParametrosMundo.delEnunciado());
    }

    /**
     * Aplica el archivo tal como esta en disco, tras editarlo a mano. Si es invalido se
     * lanza {@link ParametrosMundoInvalidos} y la version vigente no cambia.
     */
    public synchronized Versionados recargar() {
        ParametrosMundo leidos = leer();
        if (!leidos.equals(vigente.parametros())) {
            vigente = new Versionados(vigente.version() + 1, leidos);
        }
        return vigente;
    }

    /**
     * Interpreta un JSON con el mismo formato del archivo. Lo usa el endpoint de
     * actualizacion para obtener todos los errores de validacion y no solo el de Jackson.
     */
    public ParametrosMundo interpretar(String texto) {
        try {
            return json.readValue(texto, ParametrosMundo.class);
        } catch (JsonProcessingException e) {
            ParametrosMundoInvalidos invalidos = buscarInvalidos(e);
            if (invalidos != null) {
                throw invalidos;
            }
            throw new IllegalArgumentException("JSON de parametros mal formado: " + e.getOriginalMessage(), e);
        }
    }

    // ---- Archivo ---------------------------------------------------------------

    private ParametrosMundo leer() {
        try {
            return interpretar(Files.readString(archivo));
        } catch (IOException e) {
            throw new ErrorDeDatos("No se pudo leer el archivo de parametros " + archivo, e);
        } catch (ParametrosMundoInvalidos e) {
            throw new ParametrosMundoInvalidos(e.errores().stream()
                    .map(error -> archivo.getFileName() + ": " + error).toList());
        }
    }

    private void escribir(ParametrosMundo parametros) {
        try {
            Path padre = archivo.getParent();
            if (padre != null) {
                Files.createDirectories(padre);
            }
            Path temporal = archivo.resolveSibling(archivo.getFileName() + ".tmp");
            json.writeValue(temporal.toFile(), parametros);
            try {
                Files.move(temporal, archivo, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporal, archivo, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new ErrorDeDatos("No se pudo guardar el archivo de parametros " + archivo, e);
        }
    }

    /** Jackson envuelve la excepcion del constructor del record; se busca en la cadena de causas. */
    private static ParametrosMundoInvalidos buscarInvalidos(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof ParametrosMundoInvalidos invalidos) {
                return invalidos;
            }
        }
        return null;
    }
}
