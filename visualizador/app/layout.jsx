import 'leaflet/dist/leaflet.css';
import '../src/estilos.css';
import ProveedorConsultas from './proveedorConsultas.jsx';

export const metadata = {
    title: 'KindBox · Centro de reparto',
    description: 'Planificación y monitoreo de rutas de reparto de PaqRap.',
    icons: { icon: '/marca.svg' },
};
export default function Disposicion({ children: contenido }) {
    return <html lang="es-PE"><body><ProveedorConsultas>{contenido}</ProveedorConsultas></body></html>;
}
