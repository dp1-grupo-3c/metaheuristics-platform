import Visualizador from '../visualizador.jsx';
const paneles = { acceso: 'sesion', panel: 'metricas', pedidos: 'pedidos', simulacion: 'configuracion', reportes: 'metricas', configuracion: 'configuracion', seguimiento: 'pedidos' };
export function generateStaticParams() { return Object.keys(paneles).map(vista => ({ vista })); }
export default async function Pagina({ params: parametros }) {
    const { vista } = await parametros;
    return <Visualizador vista={paneles[vista]} />;
}
