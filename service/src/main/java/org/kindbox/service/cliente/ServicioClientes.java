package org.kindbox.service.cliente;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.kindbox.core.modelo.Ciudad;
import org.kindbox.service.configuracion.PropiedadesKindBox;
import org.kindbox.service.error.ConflictoDeEstado;
import org.kindbox.service.error.ErrorDeDatos;
import org.kindbox.service.error.RecursoNoEncontrado;
import org.kindbox.service.error.SolicitudInvalida;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Registro de clientes persistido en un archivo JSON.
 *
 * <p>A diferencia de la operacion diaria, los clientes sobreviven al reinicio del proceso:
 * cada alta o edicion reescribe el archivo completo mediante un temporal y un movimiento
 * atomico, de modo que una caida a mitad de escritura no deja el archivo truncado. Un archivo
 * ilegible detiene el arranque en lugar de sobrescribirse con un registro vacio.</p>
 */
@Service
public class ServicioClientes {
    private static final Pattern DNI = Pattern.compile("\\d{8}");
    private static final Pattern RUC = Pattern.compile("(10|20)\\d{9}");
    private static final Pattern TELEFONO = Pattern.compile("[0-9+ ]{7,15}");
    private static final int[] PESOS_RUC = {5, 4, 3, 2, 7, 6, 5, 4, 3, 2};

    private final Path archivo;
    private final ObjectMapper json;
    private final Clock reloj;
    private final Map<String, Cliente> clientes = new LinkedHashMap<>();

    @Autowired
    public ServicioClientes(PropiedadesKindBox propiedades, ObjectMapper json) {
        this(Path.of(propiedades.getArchivoClientes()), json, Clock.systemUTC());
    }

    public ServicioClientes(Path archivo, ObjectMapper json, Clock reloj) {
        this.archivo = archivo.toAbsolutePath();
        // El formato del archivo no debe depender de la configuracion del mapper inyectado.
        this.json = json.copy().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.reloj = reloj;
        for (Cliente cliente : leer()) clientes.put(cliente.documento(), cliente);
    }

    public synchronized Cliente registrar(SolicitudCliente solicitud) {
        Instant ahora = Instant.now(reloj);
        Cliente cliente = validar(solicitud, ahora, ahora);
        if (clientes.containsKey(cliente.documento())) {
            throw new ConflictoDeEstado("Ya existe un cliente con el documento " + cliente.documento());
        }
        clientes.put(cliente.documento(), cliente);
        guardarODeshacer(cliente.documento(), null);
        return cliente;
    }

    /** El documento y el tipo identifican al cliente y no se pueden cambiar. */
    public synchronized Cliente actualizar(String documento, SolicitudCliente solicitud) {
        Cliente anterior = buscar(documento);
        if (solicitud == null) throw new SolicitudInvalida("Indique los datos del cliente");
        if (solicitud.documento() != null && !solicitud.documento().trim().equals(anterior.documento())) {
            throw new SolicitudInvalida("El documento de un cliente no se puede cambiar");
        }
        if (solicitud.tipo() != null && solicitud.tipo() != anterior.tipo()) {
            throw new SolicitudInvalida("El tipo de un cliente no se puede cambiar");
        }
        Cliente nuevo = validar(new SolicitudCliente(anterior.tipo(), anterior.documento(), solicitud.nombre(),
                solicitud.telefono(), solicitud.contacto(), solicitud.direccion(), solicitud.x(), solicitud.y()),
                anterior.registrado(), Instant.now(reloj));
        clientes.put(anterior.documento(), nuevo);
        guardarODeshacer(anterior.documento(), anterior);
        return nuevo;
    }

    public synchronized Cliente buscar(String documento) {
        Cliente cliente = documento == null ? null : clientes.get(documento.trim());
        if (cliente == null) throw new RecursoNoEncontrado("No hay ningun cliente con el documento " + documento);
        return cliente;
    }

    /** Coincide por inicio del documento o por parte del nombre, sin distinguir mayusculas. */
    public synchronized List<Cliente> listar(String busqueda) {
        String filtro = busqueda == null ? "" : busqueda.trim().toLowerCase(Locale.ROOT);
        return clientes.values().stream()
                .filter(c -> filtro.isEmpty() || c.documento().startsWith(filtro)
                        || c.nombre().toLowerCase(Locale.ROOT).contains(filtro))
                .sorted(Comparator.comparing(Cliente::nombre, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    // ---- Validacion ----------------------------------------------------------

    static Cliente validar(SolicitudCliente s, Instant registrado, Instant actualizado) {
        if (s == null) throw new SolicitudInvalida("Indique los datos del cliente");
        if (s.tipo() == null) throw new SolicitudInvalida("Indique el tipo de cliente: PERSONA o EMPRESA");
        String documento = s.documento() == null ? "" : s.documento().trim();
        if (s.tipo() == TipoCliente.PERSONA && !DNI.matcher(documento).matches()) {
            throw new SolicitudInvalida("El DNI debe tener 8 digitos");
        }
        if (s.tipo() == TipoCliente.EMPRESA) {
            if (!documento.matches("\\d{11}")) throw new SolicitudInvalida("El RUC debe tener 11 digitos");
            if (!RUC.matcher(documento).matches()) throw new SolicitudInvalida("El RUC debe empezar con 10 o 20");
            if (!rucValido(documento)) throw new SolicitudInvalida("El RUC no es valido: su digito verificador no coincide");
        }
        String nombre = texto(s.nombre(), "nombre", 200);
        String telefono = texto(s.telefono(), "telefono", 40);
        if (!TELEFONO.matcher(telefono).matches()) throw new SolicitudInvalida("El telefono debe tener de 7 a 15 digitos");
        String direccion = texto(s.direccion(), "direccion", 300);
        String contacto = s.tipo() == TipoCliente.EMPRESA && s.contacto() != null && !s.contacto().isBlank()
                ? texto(s.contacto(), "contacto", 200) : null;
        if (s.x() == null || s.y() == null || !Ciudad.dentro(s.x(), s.y())) {
            throw new SolicitudInvalida("El nodo de entrega debe estar en la ciudad: X de 0 a "
                    + Ciudad.LARGO_KM + " e Y de 0 a " + Ciudad.ANCHO_KM);
        }
        return new Cliente(s.tipo(), documento, nombre, telefono, contacto, direccion, s.x(), s.y(), registrado, actualizado);
    }

    /** Digito verificador de SUNAT: modulo 11 sobre los diez primeros digitos. */
    static boolean rucValido(String ruc) {
        int suma = 0;
        for (int i = 0; i < PESOS_RUC.length; i++) suma += (ruc.charAt(i) - '0') * PESOS_RUC[i];
        int resto = 11 - suma % 11;
        int verificador = resto == 10 ? 0 : resto == 11 ? 1 : resto;
        return verificador == ruc.charAt(10) - '0';
    }

    private static String texto(String valor, String campo, int limite) {
        if (valor == null || valor.isBlank() || valor.trim().length() > limite) {
            throw new SolicitudInvalida("El campo " + campo + " es obligatorio y admite hasta " + limite + " caracteres");
        }
        return valor.trim();
    }

    // ---- Archivo ---------------------------------------------------------------

    private List<Cliente> leer() {
        if (!Files.exists(archivo)) return List.of();
        try {
            return json.readValue(archivo.toFile(), new TypeReference<List<Cliente>>() { });
        } catch (IOException e) {
            throw new ErrorDeDatos("No se pudo leer el archivo de clientes " + archivo, e);
        }
    }

    /** Si la escritura falla, la memoria vuelve a coincidir con el archivo. */
    private void guardarODeshacer(String documento, Cliente anterior) {
        try {
            guardar();
        } catch (IOException e) {
            if (anterior == null) clientes.remove(documento);
            else clientes.put(documento, anterior);
            throw new ErrorDeDatos("No se pudo guardar el archivo de clientes " + archivo, e);
        }
    }

    private void guardar() throws IOException {
        Files.createDirectories(archivo.getParent());
        Path temporal = archivo.resolveSibling(archivo.getFileName() + ".tmp");
        json.writerWithDefaultPrettyPrinter().writeValue(temporal.toFile(), new ArrayList<>(clientes.values()));
        try {
            Files.move(temporal, archivo, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporal, archivo, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
