# Prompt bertahap (hemat token). Kirim satu per satu, tempel hanya file yang diminta.

## 0. Konteks sekali saja
```json
{"role":"Android/Kotlin+WebView dev","app":"TimeFlow","stack":"Kotlin, Gradle 8.7, WebView UI (web/index.html), Termux build","rules":["minimal diff only","no full-file rewrites","reply with patches (file, old, new)","no explanations unless asked"]}
```

## 1. Alarm bug
```json
{"task":"fix","bug":["alarm delete/disable fails","save button unreliable"],"hint":"check bridge arg key collision with request id; day chips use dataset.v not .value","input":["web/index.html:nat()","NativeBridge.kt:alarm.*"],"output":"patches"}
```

## 2. Notch core
```json
{"task":"add","feature":"dynamic island overlay","impl":"NotificationListenerService hosts TYPE_APPLICATION_OVERLAY view","states":["idle","media_compact","notif_banner","media_full"],"anim":"ValueAnimator size+radius, overshoot on grow","files":["NotchView.kt","NotchListener.kt","NotchPrefs.kt"],"output":"code only"}
```

## 3. Notch settings
```json
{"task":"add","ui":"settings sheet","controls":{"toggles":["enabled","idleVisible","showMedia","showNotif","hideLandscape"],"sliders":{"width":[60,260],"height":[20,60],"top":[0,90],"x":[-160,160],"scale":[80,130],"autoHide":[2,15]}},"bridge":["notch.get","notch.set","notch.reset","notch.test","notch.perm"],"live_apply":true,"debounce_ms":120,"merge_pending":true}
```

## 4. Permissions
```json
{"task":"manifest+flow","perms":["SYSTEM_ALERT_WINDOW","BIND_NOTIFICATION_LISTENER_SERVICE","QUERY_ALL_PACKAGES","POST_NOTIFICATIONS","REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"],"note":"Android13+ sideload needs 'Allow restricted settings' for notification access"}
```

## 5. Debug (hanya jika build error)
```json
{"task":"fix compile","paste":"<only the first 15 error lines>","output":"patch per file"}
```
