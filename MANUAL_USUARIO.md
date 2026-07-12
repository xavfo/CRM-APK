# Manual de Usuario — QCRM APK

## 1. Requisitos del dispositivo

- **Android** 5.0 (API 21) o superior
- **GPS** activado (para llenado automático de ubicación)
- **Conexión a Internet** (para uso normal; el modo offline funciona sin conexión)

## 2. Funcionalidades principales

### 2.1 Modo offline

La aplicación puede funcionar sin conexión a Internet. Cuando el dispositivo se desconecta:

1. Las páginas ya visitadas se cargan desde la **caché local**
2. Los formularios que se envíen (crear Tareas, Leads, etc.) se guardan **automáticamente** en una cola local
3. Aparece un **círculo rojo con un número** en la esquina superior derecha indicando cuántos registros están pendientes de sincronizar

Cuando la conexión se restablece:

1. La aplicación lo nota automáticamente
2. Los registros guardados se **sincronizan uno por uno** en orden
3. Aparecerá un mensaje "Sincronización completada" cuando terminen todos

### 2.2 Llenado automático de GPS

Al crear una **Tarea** (o cualquier registro con campos de ubicación), la aplicación intenta llenar automáticamente:

- Campos llamados `latitude` / `lat` → se llena con la latitud actual
- Campos llamados `longitude` / `lng` / `lon` → se llena con la longitud actual
- Campos con nombre que contenga `gps`, `location`, `coordinates` o `ubicación` → se llena con `latitud, longitud`

**Permisos requeridos:** La aplicación pedirá permiso de ubicación la primera vez que sea necesario.

### 2.3 Rastreo GPS (tracking)

La aplicación puede registrar ubicaciones periódicamente:

- **Iniciar tracking:** Cada 5 minutos se guarda la ubicación en la base de datos local
- **Detener tracking:** Deja de registrar ubicaciones
- **Ver logs:** Se puede consultar el historial de ubicaciones guardadas
- **Limpiar logs:** Borra todos los registros después de sincronizarlos

### 2.4 Indicador de sincronización pendiente

Un **círculo rojo con número** aparece en la esquina superior derecha cuando hay registros sin sincronizar:

- **Número:** Cantidad de registros pendientes
- **Al hacer clic:** Fuerza la sincronización manual de los registros pendientes
- **Desaparece:** Cuando todos los registros se han sincronizado exitosamente

## 3. Ciclo de trabajo offline

```
Usuario llena formulario (ej: crear Tarea)
         │
         ▼
  ┌─ ¿Hay conexión? ──┐
  │   NO               │  SÍ
  ▼                    ▼
Guardar en          Enviar
cola local          normalmente
         │
         ▼
  (aparece círculo rojo)
         │
         ▼
  ¿Se restableció la conexión?
         │
         ▼
  Sincronizar uno por uno
         │
         ▼
  (círculo rojo desaparece)
```

## 4. APIs JavaScript disponibles

El CRM web puede usar estas funciones desde la consola o desde su propio código:

| Función | Descripción |
|---|---|
| `AndroidBridge.isOnline()` | Retorna `true`/`false` si hay conexión |
| `AndroidBridge.getLocation()` | Retorna `{"latitude":...,"longitude":...,"accuracy":...,"timestamp":...}` |
| `AndroidBridge.showToast("mensaje")` | Muestra un mensaje tipo Toast |
| `AndroidBridge.hasLocationPermission()` | Verifica si hay permiso de GPS |
| `AndroidBridge.requestLocationPermission()` | Solicita permiso de GPS |
| `AndroidBridge.storeOffline(clave, json)` | Guarda datos offline |
| `AndroidBridge.fetchOffline(clave)` | Recupera datos offline |
| `AndroidBridge.getPendingSyncCount()` | Retorna cuántos registros faltan sincronizar |
| `AndroidBridge.triggerSync()` | Fuerza la sincronización |
| `AndroidBridge.getTrackingLogs()` | Retorna todos los logs GPS como JSON |
| `AndroidBridge.clearTrackingLogs()` | Limpia los logs GPS |
| `AndroidBridge.startTracking(ms)` | Inicia tracking GPS cada N milisegundos |
| `AndroidBridge.stopTracking()` | Detiene tracking GPS |

## 5. Solución de problemas

### "No hay conexión" en modo offline

- Asegúrate de haber visitado la página **estando conectado** al menos una vez
- La caché solo guarda páginas previamente cargadas

### GPS no se llena automáticamente

- Concede el permiso de ubicación cuando el sistema lo solicite
- Activa el GPS en el dispositivo
- Espera unos segundos a que el GPS obtenga una ubicación

### La sincronización no se completa

- Verifica que la conexión a Internet sea estable
- Si un registro falla, la aplicación lo reintenta automáticamente
- Puedes hacer clic en el círculo rojo para forzar un reintento manual

### El círculo rojo no desaparece

- La sincronización podría estar en proceso
- Revisa la conexión a Internet
- Toca el círculo rojo para forzar la sincronización
