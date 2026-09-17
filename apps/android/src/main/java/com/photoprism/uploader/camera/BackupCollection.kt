package com.photoprism.uploader.camera

/** Stable IDs also scope the service inventory, local index and periodic work. */
enum class BackupCollection(val id: String, val title: String, val folder: String, val syntheticFolder: String, val video: Boolean = false) {
    CAMERA("camera", "Camera", "DCIM/Camera/", "camera"),
    WHATSAPP_IMAGES("whatsapp-images", "WhatsApp images", "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/", "whatsapp"),
    WHATSAPP_VIDEOS("whatsapp-videos", "WhatsApp videos", "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video/", "whatsapp-videos", true);
}
