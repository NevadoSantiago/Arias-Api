# Agente de impresión de comandas (Arias Ticketera)

## Qué hace

Un programa chico que corre solo en la PC del local. Cada 5 segundos le pregunta al sistema de
Arias si hay comandas nuevas y las manda a la impresora de cocina (192.168.0.121, puerto 9100).
Después avisa si imprimió bien o no. Arranca solo al prender la PC y se reinicia si se cae.

## Instalación

1. Copiá la carpeta `print-agent` completa a la PC (pendrive o descarga), por ejemplo al Escritorio.
2. Abrí PowerShell como administrador: botón Inicio, escribí `PowerShell`, clic derecho en
   "Windows PowerShell" y elegí **Ejecutar como administrador**.
3. Entrá a la carpeta y ejecutá el instalador (cambiá la ruta por la tuya):

   ```powershell
   cd "$env:USERPROFILE\Desktop\print-agent"
   powershell -ExecutionPolicy Bypass -File .\install.ps1 -ApiUrl https://api.ariasbodegon.com
   ```

4. Al terminar, el instalador muestra una línea así:

   ```
   PRINT_AGENT_KEY_SHA256 = 3f2a...
   ```

   Copiá ese valor y pegalo en **Railway**: servicio del backend, pestaña *Variables*, variable
   `PRINT_AGENT_KEY_SHA256`. Guardá y esperá que Railway redeploye.
   (Se muestra solo el hash; la clave real queda guardada en la PC y no se muestra.)

El agente se instala en `C:\Arias-ticketera`. Volver a correr el instalador es seguro: conserva la
clave y vuelve a registrar la tarea (y vuelve a mostrar el hash).

## Cómo comprobar que funciona

- Log: `Get-Content C:\Arias-ticketera\logs\agent.log -Tail 30`
- Tarea: `Get-ScheduledTask -TaskName "Arias Ticketera" | Get-ScheduledTaskInfo` (estado "Running"),
  o abrí el *Programador de tareas* de Windows y buscá "Arias Ticketera".
- En el log aparece cada comanda: número, impresora y resultado. Si está sin actividad escribe una
  línea de "heartbeat" cada 10 minutos. El log nunca guarda la clave ni el contenido del ticket.

## Cambiar la clave

1. En PowerShell como administrador, dentro de la carpeta `print-agent`:

   ```powershell
   powershell -ExecutionPolicy Bypass -File .\install.ps1 -NewKey
   ```

2. Copiá el nuevo `PRINT_AGENT_KEY_SHA256` a Railway. Hasta hacerlo, el log mostrará errores 401.

## Desinstalar

```powershell
powershell -ExecutionPolicy Bypass -File .\uninstall.ps1
```

Elimina la tarea y conserva la carpeta. Para borrar también los archivos agregá `-RemoveFiles`.

## Problemas frecuentes

- **Error 401 en el log**: la clave no coincide con la de Railway. Verificá que
  `PRINT_AGENT_KEY_SHA256` tenga el hash que mostró el instalador (volvé a correr `install.ps1`
  para verlo de nuevo). El agente espera 60 segundos entre reintentos.
- **Impresora inaccesible** ("print error" en el log): revisá que esté prendida, con papel y en la
  red, y que responda en 192.168.0.121. La comanda queda marcada como fallida en el sistema.
- **PC apagada**: no se imprime nada. Las comandas pendientes **vencen a los 30 minutos**; las
  más viejas no se imprimen al volver a prender la PC.
- **Dos copias del agente**: no pasa; si ya hay una corriendo, la segunda termina sola.
