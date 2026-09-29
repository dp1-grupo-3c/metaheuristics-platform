"""Comprueba el proxy HTTPS local con una cuenta temporal de pruebas, nunca de la VM."""
import base64
import http.client
import socket
import ssl

contexto = ssl._create_unverified_context()
credencial = base64.b64encode(b'prueba:verificacion-local').decode()

def consultar(ruta, autenticar=True, origen=None):
    conexion = http.client.HTTPSConnection('127.0.0.1', 18443, context=contexto, timeout=10)
    cabeceras = {'Authorization': 'Basic ' + credencial} if autenticar else {}
    if origen:
        cabeceras['Origin'] = origen
    conexion.request('GET', ruta, headers=cabeceras)
    respuesta = conexion.getresponse()
    codigo = respuesta.status
    respuesta.read()
    conexion.close()
    return codigo

assert consultar('/api/salud', False) == 401
assert consultar('/api/salud') == 200
assert consultar('/simulacion/') == 200
assert consultar('/api/salud', origen='https://sitio-ajeno.example') == 403
with socket.create_connection(('127.0.0.1', 18443), timeout=10) as conexion:
    with contexto.wrap_socket(conexion, server_hostname='localhost') as canal:
        peticion = ('GET /ws/simulacion HTTP/1.1\r\nHost: 1inf54-983-3c.inf.pucp.edu.pe\r\n'
                    'Origin: https://1inf54-983-3c.inf.pucp.edu.pe\r\n'
                    'Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\n'
                    'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nAuthorization: Basic '
                    + credencial + '\r\n\r\n')
        canal.sendall(peticion.encode())
        cabecera = canal.recv(4096)
        assert cabecera.split(b'\r\n', 1)[0].split()[1] == b'101', cabecera
print('HTTPS: acceso anónimo 401; API y página 200; origen ajeno 403; WebSocket 101.')
