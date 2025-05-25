package expo.modules.shareintent

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Parcelable
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import coil.ImageLoader
import coil.request.ImageRequest
import coil.target.Target
import expo.modules.kotlin.exception.Exceptions
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.kotlin.Promise
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Date
import java.util.UUID
import androidx.core.net.toUri
import androidx.core.graphics.createBitmap
import androidx.core.graphics.toColorInt
import androidx.core.graphics.scale

/**
 * Expo module for handling shared content from other apps
 */
class ExpoShareIntentModule : Module() {
    /**
     * The application context
     */
    private val context: Context
        get() = appContext.reactContext ?: throw Exceptions.ReactContextLost()

    /**
     * Current activity if available
     */
    private val currentActivity: Activity?
        get() = appContext.currentActivity

    companion object {
        private var instance: ExpoShareIntentModule? = null
        private const val TEXT_SHARE_CATEGORY =
            "expo.modules.shareintent.category.TEXT_SHARE_TARGET"
        private const val SEND_MESSAGE_CAPABILITY = "actions.intent.SEND_MESSAGE"
        private const val RECEIVE_MESSAGE_CAPABILITY = "actions.intent.RECEIVE_MESSAGE"
        private const val ICON_SIZE = 72
        private const val CANVAS_SIZE = 108

        /**
         * Notifies about received share intent with the shared content
         * @param value The shared content details
         */
        private fun notifyShareIntent(value: Any) {
            notifyState("pending")
            instance?.sendEvent("onChange", mapOf("data" to value))
        }

        /**
         * Notifies about state changes
         * @param state Current state
         */
        private fun notifyState(state: String) {
            instance?.sendEvent("onStateChange", mapOf("data" to state))
        }

        /**
         * Notifies about errors
         * @param message Error message
         */
        private fun notifyError(message: String) {
            instance?.sendEvent("onError", mapOf("data" to message))
        }

        /**
         * Notifies about successful donate operation
         * @param data The data associated with the donate operation
         */
        private fun notifyDonate(data: Map<String, String?>) {
            instance?.sendEvent("onDonate", mapOf("data" to data))
        }

        /**
         * Extracts file information from a URI
         * @param uri Content URI
         * @return Map containing file details
         */
        @SuppressLint("Range")
        private fun getFileInfo(uri: Uri): Map<String, String?> {
            // Get content resolver
            val resolver = getContentResolver()
            if (resolver == null) {
                notifyError("Cannot get resolver (getFileInfo)")
                return createBasicFileInfo(uri)
            }

            // Query file metadata
            return try {
                val queryResult = resolver.query(uri, null, null, null, null)
                    ?: return createBasicFileInfo(uri)

                // Extract basic file information
                queryResult.use { cursor ->
                    cursor.moveToFirst()
                    val fileInfo = extractFileInfoFromCursor(cursor, resolver, uri)

                    // Extract media-specific information based on mime type
                    when {
                        fileInfo["mimeType"]?.startsWith("image/") == true ->
                            fileInfo + extractImageDimensions(resolver, uri)

                        fileInfo["mimeType"]?.startsWith("video/") == true ->
                            fileInfo + extractVideoMetadata(uri)

                        else -> fileInfo
                    }
                }
            } catch (e: Exception) {
                notifyError("Error getting file info: ${e.message}")
                createBasicFileInfo(uri)
            }
        }

        /**
         * Creates basic file info when detailed info can't be retrieved
         */
        private fun createBasicFileInfo(uri: Uri): Map<String, String?> = mapOf(
            "contentUri" to uri.toString(),
            "filePath" to instance?.getAbsolutePath(uri)
        )

        /**
         * Extracts basic file information from cursor
         */
        @SuppressLint("Range")
        private fun extractFileInfoFromCursor(
            cursor: Cursor,
            resolver: ContentResolver,
            uri: Uri
        ): Map<String, String?> = mapOf(
            "contentUri" to uri.toString(),
            "filePath" to instance?.getAbsolutePath(uri),
            "fileName" to cursor.getString(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)),
            "fileSize" to cursor.getString(cursor.getColumnIndex(OpenableColumns.SIZE)),
            "mimeType" to resolver.getType(uri)
        )

        /**
         * Gets the content resolver from instance
         */
        private fun getContentResolver(): ContentResolver? =
            instance?.currentActivity?.contentResolver ?: instance?.context?.contentResolver

        /**
         * Extracts image dimensions from an image URI
         */
        private fun extractImageDimensions(
            resolver: ContentResolver,
            uri: Uri
        ): Map<String, String?> {
            return try {
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeStream(resolver.openInputStream(uri), null, options)

                mapOf(
                    "width" to options.outWidth.toString(),
                    "height" to options.outHeight.toString()
                )
            } catch (e: Exception) {
                mapOf(
                    "width" to null,
                    "height" to null
                )
            }
        }

        /**
         * Extracts video metadata from a video URI
         */
        private fun extractVideoMetadata(uri: Uri): Map<String, String?> {
            return try {
                val filePath = instance?.getAbsolutePath(uri) ?: return emptyMap()
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(filePath)

                // Extract basic dimensions
                var width =
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                var height =
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)

                // Check orientation and flip dimensions if needed
                val metaRotation =
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                        ?.toInt() ?: 0
                if (metaRotation == 90 || metaRotation == 270) {
                    val temp = width
                    width = height
                    height = temp
                }

                mapOf(
                    "width" to width,
                    "height" to height,
                    "duration" to retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                )
            } catch (e: Exception) {
                mapOf(
                    "width" to null,
                    "height" to null,
                    "duration" to null
                )
            }
        }

        /**
         * Reports shortcut usage for a specific conversation
         * @param conversationId Unique identifier for the conversation
         */
        private fun reportShortcutUsed(conversationId: String) {
            try {
                instance?.let { module ->
                    ShortcutManagerCompat.reportShortcutUsed(module.context, conversationId)
                }
            } catch (e: Exception) {
                notifyError("Error reporting shortcut usage: ${e.message}")
            }
        }

        /**
         * Sets dynamic shortcuts in order of importance
         * @param shortcuts List of shortcuts ordered by importance (most important first)
         */
        private fun setDynamicShortcuts(shortcuts: List<ShortcutInfoCompat>) {
            try {
                instance?.let { module ->
                    ShortcutManagerCompat.setDynamicShortcuts(module.context, shortcuts)
                }
            } catch (e: Exception) {
                notifyError("Error setting dynamic shortcuts: ${e.message}")
            }
        }

        /**
         * Removes a long-lived shortcut
         * @param shortcutId ID of the shortcut to remove
         */
        private fun removeLongLivedShortcut(shortcutId: String) {
            try {
                instance?.let { module ->
                    ShortcutManagerCompat.removeLongLivedShortcuts(
                        module.context,
                        listOf(shortcutId)
                    )
                }
            } catch (e: Exception) {
                notifyError("Error removing shortcut: ${e.message}")
            }
        }

        /**
         * Handles intent with shared content
         * @param intent The received intent
         */
        fun handleShareIntent(intent: Intent) {
            // Early return if no type
            val intentType = intent.type ?: return

            when {
                // Handle text/plain content
                intentType.startsWith("text/plain") -> handleTextShare(intent)

                // Handle file content
                else -> handleFileShare(intent)
            }
        }

        /**
         * Handles text or URL sharing
         */
        private fun handleTextShare(intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SEND -> {
                    notifyShareIntent(
                        mapOf(
                            "text" to intent.getStringExtra(Intent.EXTRA_TEXT),
                            "type" to "text",
                            "meta" to mapOf(
                                "title" to intent.getCharSequenceExtra(Intent.EXTRA_TITLE),
                            )
                        )
                    )
                }

                Intent.ACTION_VIEW -> {
                    notifyShareIntent(
                        mapOf(
                            "text" to intent.dataString,
                            "type" to "text"
                        )
                    )
                }

                else -> {
                    notifyError("Invalid action for text sharing: ${intent.action}")
                }
            }
        }

        /**
         * Handles file sharing (single or multiple)
         */
        private fun handleFileShare(intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SEND -> {
                    val uri = intent.parcelable<Uri>(Intent.EXTRA_STREAM)
                    if (uri != null) {
                        notifyShareIntent(
                            mapOf(
                                "files" to arrayOf(getFileInfo(uri)),
                                "type" to "file"
                            )
                        )
                    } else {
                        notifyError("Empty uri for file sharing: ${intent.action}")
                    }
                }

                Intent.ACTION_SEND_MULTIPLE -> {
                    val uris = intent.parcelableArrayList<Uri>(Intent.EXTRA_STREAM)
                    if (uris != null) {
                        notifyShareIntent(
                            mapOf(
                                "files" to uris.map { getFileInfo(it) },
                                "type" to "file"
                            )
                        )
                    } else {
                        notifyError("Empty uris array for file sharing: ${intent.action}")
                    }
                }

                else -> {
                    notifyError("Invalid action for file sharing: ${intent.action}")
                }
            }
        }

        /*
         * https://stackoverflow.com/questions/73019160/the-getparcelableextra-method-is-deprecated
         */
        private inline fun <reified T : Parcelable> Intent.parcelable(key: String): T? = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> getParcelableExtra(
                key,
                T::class.java
            )

            else -> @Suppress("DEPRECATION") getParcelableExtra(key) as? T
        }

        private inline fun <reified T : Parcelable> Intent.parcelableArrayList(key: String): ArrayList<T>? =
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> getParcelableArrayListExtra(
                    key,
                    T::class.java
                )

                else -> @Suppress("DEPRECATION") getParcelableArrayListExtra(key)
            }
    }

    // See https://docs.expo.dev/modules/module-api
    override fun definition() = ModuleDefinition {
        Name("ExpoShareIntentModule")

        Events("onChange", "onStateChange", "onError", "onDonate")

        AsyncFunction("getShareIntent") { _: String ->
            // get the Intent from onCreate activity (app not running in background)
            ExpoShareIntentSingleton.isPending = false
            if (ExpoShareIntentSingleton.intent?.type != null) {
                handleShareIntent(ExpoShareIntentSingleton.intent!!);
                ExpoShareIntentSingleton.intent = null
            }
        }

        AsyncFunction("donateSendMessage") { conversationId: String, name: String, imageURL: String?, content: String?, promise: Promise ->
            donateSendMessage(conversationId, name, imageURL, content, promise)
        }

        Function("clearShareIntent") { _: String ->
            ExpoShareIntentSingleton.intent = null
        }

        Function("hasShareIntent") { _: String ->
            ExpoShareIntentSingleton.isPending
        }

        Function("reportShortcutUsed") { conversationId: String ->
            reportShortcutUsed(conversationId)
        }

        AsyncFunction("setDynamicShortcuts") { shortcuts: List<Map<String, Any>> ->
            val shortcutList = shortcuts.mapIndexed { index, shortcutData ->
                createShortcutFromData(shortcutData, index)
            }.filterNotNull()

            setDynamicShortcuts(shortcutList)
        }

        Function("removeLongLivedShortcut") { shortcutId: String ->
            removeLongLivedShortcut(shortcutId)
        }

        AsyncFunction("publishDirectShareTargets") { contacts: List<Map<String, Any>> ->
            publishDirectShareTargets(contacts)
        }

        OnNewIntent {
            handleShareIntent(it)
        }

        OnCreate {
            instance = this@ExpoShareIntentModule
        }

        OnDestroy {
            instance = null
        }
    }

    /**
     * Creates a ShortcutInfoCompat from the provided data
     * @param shortcutData Map containing shortcut data
     * @param position Position in the list (for ordering)
     * @return ShortcutInfoCompat or null if creation failed
     */
    private fun createShortcutFromData(
        shortcutData: Map<String, Any>,
        position: Int
    ): ShortcutInfoCompat? {
        try {
            val activity = currentActivity ?: return null
            val conversationId = shortcutData["id"] as? String ?: return null
            val name = shortcutData["name"] as? String ?: return null
            val imageURL = shortcutData["imageURL"] as? String

            // Create intent for the shortcut
            val targetClass = activity.javaClass
            val intent = Intent(Intent.ACTION_SEND).apply {
                setClassName(activity.packageName, targetClass.name)
                putExtra(Intent.EXTRA_SHORTCUT_ID, conversationId)
                type = "text/plain"
                flags = Intent.FLAG_ACTIVITY_CLEAR_TASK
            }

            // Shortcut categories - used to match shortcuts with share target definitions
            val categories = setOf(TEXT_SHARE_CATEGORY)

            // Get the short label (first name or initial part of name)
            val shortLabel = name.split(" ").firstOrNull() ?: name

            // Create shortcut builder with all required properties
            val builder = ShortcutInfoCompat.Builder(context, conversationId)
                .setShortLabel(shortLabel)
                .setLongLabel(name)
                .setIntent(intent)
                .setRank(position)  // Set the rank/position for ordering
                .setLongLived(true)
                .setCategories(categories)
                .addCapabilityBinding(SEND_MESSAGE_CAPABILITY)

            // Handle icon loading synchronously for this method
            val icon = createDefaultIcon(name)
            builder.setIcon(icon)

            // Asynchronously update the icon if URL is provided
            if (!imageURL.isNullOrEmpty()) {
                loadImageIcon(imageURL, name) { updatedIcon ->
                    try {
                        val updatedShortcut = builder.setIcon(updatedIcon).build()
                        ShortcutManagerCompat.updateShortcuts(context, listOf(updatedShortcut))
                    } catch (e: Exception) {
                        Log.e("ExpoShareIntent", "Error updating shortcut icon: ${e.message}")
                    }
                }
            }

            return builder.build()
        } catch (e: Exception) {
            Log.e("ExpoShareIntent", "Error creating shortcut: ${e.message}")
            return null
        }
    }

    /**
     * Creates a default icon for shortcuts when async loading isn't appropriate
     * @param name Name to use for monogram
     * @return IconCompat for the shortcut
     */
    private fun createDefaultIcon(name: String): IconCompat {
        val monogram = createMonogramAvatar(name)
        val adaptiveBitmap = createAdaptiveBitmap(monogram)
        return IconCompat.createWithAdaptiveBitmap(adaptiveBitmap)
    }

    /**
     * Get a file path from a Uri. This will get the path for Storage Access
     * Framework Documents, as well as the _data field for the MediaStore and
     * other file-based ContentProviders.
     *
     * @param uri The Uri to query.
     * @return The absolute file path or null if not found
     */
    fun getAbsolutePath(uri: Uri): String? {
        return try {
            when {
                // Handle document URIs through the DocumentProvider
                DocumentsContract.isDocumentUri(context, uri) -> getDocumentProviderPath(uri)

                // Handle content scheme URIs
                "content".equals(uri.scheme, ignoreCase = true) -> getDataColumn(uri, null, null)

                // Default to the URI path for other schemes
                else -> uri.path
            }
        } catch (e: Exception) {
            e.printStackTrace()
            notifyError("Cannot retrieve absoluteFilePath for $uri: ${e.message}")
            null
        }
    }

    /**
     * Handles document provider URIs based on their authority
     */
    private fun getDocumentProviderPath(uri: Uri): String? {
        val docId = DocumentsContract.getDocumentId(uri)

        return when {
            // External storage documents
            isExternalStorageDocument(uri) -> handleExternalStorageDocument(docId)

            // Downloads documents
            isDownloadsDocument(uri) -> handleDownloadsDocument(uri, docId)

            // Media documents (images, videos, audio)
            isMediaDocument(uri) -> handleMediaDocument(uri, docId)

            // Other document types
            else -> null
        }
    }

    /**
     * Handles external storage document URIs
     */
    private fun handleExternalStorageDocument(docId: String): String? {
        val split = docId.split(":", limit = 2)
        val type = split[0]

        return if ("primary".equals(type, ignoreCase = true) && split.size > 1) {
            "${Environment.getExternalStorageDirectory()}/${split[1]}"
        } else {
            getDataColumn(uri = docId.toUri(), selection = null, selectionArgs = null)
        }
    }

    /**
     * Handles downloads document URIs
     */
    private fun handleDownloadsDocument(uri: Uri, docId: String): String? {
        return try {
            val contentUri = ContentUris.withAppendedId(
                "content://downloads/public_downloads".toUri(),
                docId.toLong()
            )
            getDataColumn(contentUri, null, null)
        } catch (e: Exception) {
            // Fallback if parsing fails
            getDataColumn(uri, null, null)
        }
    }

    /**
     * Handles media document URIs (images, videos, audio)
     */
    private fun handleMediaDocument(uri: Uri, docId: String): String? {
        val split = docId.split(":", limit = 2)
        val type = split[0]

        // Early return if we don't have the expected format
        if (split.size < 2) return null

        // Select the appropriate content URI based on media type
        val contentUri = when (type) {
            "image" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            "audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            else -> return null
        }

        // Query the content provider
        val selection = "_id=?"
        val selectionArgs = arrayOf(split[1])
        return getDataColumn(contentUri, selection, selectionArgs)
    }

    /**
     * Get the value of the data column for this Uri. This is useful for
     * MediaStore Uris, and other file-based ContentProviders.
     *
     * @param uri The Uri to query.
     * @param selection (Optional) Filter used in the query.
     * @param selectionArgs (Optional) Selection arguments used in the query.
     * @return The value of the _data column, which is typically a file path.
     */
    private fun getDataColumn(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<String>?
    ): String? {
        // Get content resolver
        val resolver = getContentResolver() ?: run {
            notifyError("Cannot get resolver (getDataColumn)")
            return null
        }

        // Handle content with authority by copying to cache
        if (uri.authority != null) {
            return copyUriToCache(uri, resolver, selection, selectionArgs)
        }

        // Otherwise try to get the direct file path
        return queryForDataColumn(resolver, uri, selection, selectionArgs)
    }

    /**
     * Copies URI content to cache directory and returns the path
     */
    private fun copyUriToCache(
        uri: Uri,
        resolver: ContentResolver,
        selection: String?,
        selectionArgs: Array<String>?
    ): String? {
        // Try to get the filename
        val targetFile = getTargetFile(uri, resolver, selection, selectionArgs) ?: return null

        // Copy the file content
        try {
            resolver.openInputStream(uri)?.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
            return targetFile.path
        } catch (e: Exception) {
            notifyError("Failed to copy file: ${e.message}")
            return null
        }
    }

    /**
     * Creates a target file in cache, either with original name or generated name
     */
    private fun getTargetFile(
        uri: Uri,
        resolver: ContentResolver,
        selection: String?,
        selectionArgs: Array<String>?
    ): File? {
        // Try to get the original filename
        return try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), selection, selectionArgs, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val columnIndex = cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)
                        val fileName = cursor.getString(columnIndex)
                        Log.i("FileDirectory", "File name: $fileName")
                        File(context.cacheDir, fileName)
                    } else {
                        createGenericFile(uri, resolver)
                    }
                } ?: createGenericFile(uri, resolver)
        } catch (e: Exception) {
            createGenericFile(uri, resolver)
        }
    }

    /**
     * Creates a generic file name based on mime type
     */
    private fun createGenericFile(uri: Uri, resolver: ContentResolver): File {
        val mimeType = resolver.getType(uri)
        val prefix = with(mimeType ?: "") {
            when {
                startsWith("image") -> "IMG"
                startsWith("video") -> "VID"
                else -> "FILE"
            }
        }
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) ?: ""
        return File(context.cacheDir, "${prefix}_${Date().time}.${extension}")
    }

    /**
     * Queries for _data column to get direct file path
     */
    private fun queryForDataColumn(
        resolver: ContentResolver,
        uri: Uri,
        selection: String?,
        selectionArgs: Array<String>?
    ): String? {
        return resolver.query(uri, arrayOf("_data"), selection, selectionArgs, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val columnIndex = cursor.getColumnIndexOrThrow("_data")
                    cursor.getString(columnIndex)
                } else {
                    null
                }
            }
    }

    /**
     * Gets the content resolver from instance
     */
    private fun getContentResolver(): ContentResolver? =
        currentActivity?.contentResolver ?: context.contentResolver

    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is ExternalStorageProvider.
     */
    private fun isExternalStorageDocument(uri: Uri): Boolean {
        return "com.android.externalstorage.documents" == uri.authority
    }

    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is DownloadsProvider.
     */
    private fun isDownloadsDocument(uri: Uri): Boolean {
        return "com.android.providers.downloads.documents" == uri.authority
    }

    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is MediaProvider.
     */
    private fun isMediaDocument(uri: Uri): Boolean {
        return "com.android.providers.media.documents" == uri.authority
    }

    /**
     * Creates a monogram avatar from name initials
     * @param name Person's name
     * @return Bitmap containing the monogram avatar
     */
    private fun createMonogramAvatar(name: String): Bitmap {
        val size = CANVAS_SIZE
        val bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)

        // Background fill
        val backgroundPaint = Paint().apply {
            color = "#3498db".toColorInt() // Blue background
            isAntiAlias = true
            style = Paint.Style.FILL
        }
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), backgroundPaint)

        // Draw initials
        val initial = name.firstOrNull()?.uppercase() ?: "?"
        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = size * 0.4f
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }

        val textBounds = Rect()
        textPaint.getTextBounds(initial, 0, initial.length, textBounds)

        // Center the text
        val xPos = size / 2
        val yPos = (size / 2 - (textPaint.descent() + textPaint.ascent()) / 2).toInt()

        canvas.drawText(initial, xPos.toFloat(), yPos.toFloat(), textPaint)

        return bitmap
    }

    /**
     * Creates an adaptive bitmap suitable for shortcuts
     * @param bitmap Source bitmap to adapt
     * @return Adapted bitmap centered in the proper canvas
     */
    private fun createAdaptiveBitmap(bitmap: Bitmap): Bitmap {
        val source = if (bitmap.width > ICON_SIZE || bitmap.height > ICON_SIZE) {
            // Scale down the bitmap
            val scaleFactor = ICON_SIZE.toFloat() / Math.max(bitmap.width, bitmap.height)
            val scaledWidth = (bitmap.width * scaleFactor).toInt()
            val scaledHeight = (bitmap.height * scaleFactor).toInt()
            bitmap.scale(scaledWidth, scaledHeight)
        } else {
            bitmap
        }

        // Create a canvas with appropriate size
        val result = createBitmap(CANVAS_SIZE, CANVAS_SIZE)
        val canvas = Canvas(result)

        // Fill with transparent background
        canvas.drawColor(Color.TRANSPARENT)

        // Center the image in the canvas
        val left = (CANVAS_SIZE - source.width) / 2
        val top = (CANVAS_SIZE - source.height) / 2

        canvas.drawBitmap(source, left.toFloat(), top.toFloat(), null)

        return result
    }

    /**
     * Loads an image from URL and converts it to IconCompat
     * @param imageUrl URL of the image to load
     * @param name Name for fallback monogram
     * @param callback Callback to receive the created IconCompat
     */
    private fun loadImageIcon(imageUrl: String?, name: String, callback: (IconCompat) -> Unit) {
        if (imageUrl == null || imageUrl.isEmpty()) {
            // If no image URL provided, create a monogram avatar
            val monogram = createMonogramAvatar(name)
            val adaptiveBitmap = createAdaptiveBitmap(monogram)
            callback(IconCompat.createWithAdaptiveBitmap(adaptiveBitmap))
            return
        }

        // Check if it's a local file
        if (imageUrl.startsWith("file://") || imageUrl.startsWith("/")) {
            try {
                val uri = if (imageUrl.startsWith("file://")) imageUrl.toUri() else Uri.fromFile(
                    File(imageUrl)
                )
                val bitmap =
                    BitmapFactory.decodeStream(context.contentResolver.openInputStream(uri))
                if (bitmap != null) {
                    val adaptiveBitmap = createAdaptiveBitmap(bitmap)
                    callback(IconCompat.createWithAdaptiveBitmap(adaptiveBitmap))
                    return
                }
            } catch (e: Exception) {
                Log.e("ExpoShareIntent", "Error loading local image: ${e.message}")
            }
        }

        // Handle remote image URL
        try {
            val imageLoader = ImageLoader(context)
            val request = ImageRequest.Builder(context)
                .data(imageUrl)
                .target(object : Target {
                    override fun onSuccess(result: Drawable) {
                        CoroutineScope(Dispatchers.IO).launch {
                            try {
                                // Convert drawable to bitmap
                                val bitmap =
                                    if (result is android.graphics.drawable.BitmapDrawable) {
                                        result.bitmap
                                    } else {
                                        val bmp = createBitmap(
                                            result.intrinsicWidth,
                                            result.intrinsicHeight
                                        )
                                        val canvas = Canvas(bmp)
                                        result.setBounds(0, 0, canvas.width, canvas.height)
                                        result.draw(canvas)
                                        bmp
                                    }

                                val adaptiveBitmap = createAdaptiveBitmap(bitmap)
                                withContext(Dispatchers.Main) {
                                    callback(IconCompat.createWithAdaptiveBitmap(adaptiveBitmap))
                                }
                            } catch (e: Exception) {
                                Log.e("ExpoShareIntent", "Error processing image: ${e.message}")
                                val fallback = createMonogramAvatar(name)
                                val adaptiveBitmap = createAdaptiveBitmap(fallback)
                                withContext(Dispatchers.Main) {
                                    callback(IconCompat.createWithAdaptiveBitmap(adaptiveBitmap))
                                }
                            }
                        }
                    }

                    override fun onError(error: Drawable?) {
                        val fallback = createMonogramAvatar(name)
                        val adaptiveBitmap = createAdaptiveBitmap(fallback)
                        callback(IconCompat.createWithAdaptiveBitmap(adaptiveBitmap))
                    }

                    override fun onStart(placeholder: Drawable?) {}
                    // Note: onStop is not needed in Coil 2.4.0
                })
                .build()

            imageLoader.enqueue(request)
        } catch (e: Exception) {
            Log.e("ExpoShareIntent", "Error loading image: ${e.message}")
            val fallback = createMonogramAvatar(name)
            val adaptiveBitmap = createAdaptiveBitmap(fallback)
            callback(IconCompat.createWithAdaptiveBitmap(adaptiveBitmap))
        }
    }

    /**
     * Creates and pushes a dynamic shortcut for direct sharing
     *
     * @param conversationId Unique identifier for the conversation/contact
     * @param name Name of the person/group
     * @param imageURL Optional URL to the profile picture
     * @param content Optional content description
     * @return Promise that resolves when shortcut is created
     */
    private fun donateSendMessage(
        conversationId: String,
        name: String,
        imageURL: String? = null,
        content: String? = null,
        promise: Promise
    ) {

        try {
            // Activity must be available for sharing shortcuts
            val activity = currentActivity
            if (activity == null) {
                promise.reject(
                    "E_NO_ACTIVITY",
                    "Activity not available for sharing shortcuts",
                    null
                )
                return
            }

            // Create intent for the shortcut
            val targetClass = activity.javaClass
            val intent = Intent(Intent.ACTION_SEND).apply {
                setClassName(activity.packageName, targetClass.name)
                putExtra(Intent.EXTRA_SHORTCUT_ID, conversationId)
                type = "text/plain"
                flags = Intent.FLAG_ACTIVITY_CLEAR_TASK
            }

            // Shortcut categories - used to match shortcuts with share target definitions
            val categories = setOf(TEXT_SHARE_CATEGORY)

            // Get the short label (first name or initial part of name)
            val shortLabel = name.split(" ").firstOrNull() ?: name

            // Set up icon loading with proper callback handling
            loadImageIcon(imageURL, name) { icon ->
                try {
                    // Create the shortcut with the loaded icon
                    val shortcutInfo = ShortcutInfoCompat.Builder(context, conversationId)
                        .setShortLabel(shortLabel)
                        .setLongLabel(name)
                        .setIcon(icon)
                        .setIntent(intent)
                        .setLongLived(true)
                        .setCategories(categories)
                        .addCapabilityBinding(SEND_MESSAGE_CAPABILITY)
                        .build()

                    // Push the dynamic shortcut
                    ShortcutManagerCompat.pushDynamicShortcut(context, shortcutInfo)

                    // Report shortcut usage
                    ShortcutManagerCompat.reportShortcutUsed(context, conversationId)
                    
                    // Notify success and resolve promise
                    val responseData = mapOf(
                        "conversationId" to conversationId,
                        "name" to name,
                        "imageURL" to imageURL,
                        "content" to content
                    )
                    notifyDonate(responseData)
                    promise.resolve(Unit)
                } catch (e: Exception) {
                    notifyError("Error creating shortcut: ${e.message}")
                    promise.reject("E_CREATE_SHORTCUT", e.message, e.cause)
                }
            }
        } catch (e: Exception) {
            notifyError("Error in donateSendMessage: ${e.message}")
            promise.reject("E_DONATE_SEND_MESSAGE", e.message, e.cause)
        }
    }

    /**
     * Publishes a list of direct share targets for contacts
     *
     * @param contacts List of contact information to create share targets for
     * @return True if successfully published, false otherwise
     */
    private fun publishDirectShareTargets(contacts: List<Map<String, Any>>): Boolean {
        try {
            val activity = currentActivity ?: return false
            val packageName = context.packageName
            val targetClass = activity.javaClass.name

            // Create shortcuts for each contact (limited to first 10 contacts)
            val shortcuts = contacts.take(10).mapIndexed { index, contact ->
                val id = contact["id"] as? String ?: UUID.randomUUID().toString()
                val name = contact["name"] as? String ?: "Unknown"
                val imageUrl = contact["imageURL"] as? String
                val shortLabel = name.split(" ").firstOrNull() ?: name

                // Create intent for shortcuts
                val intent = Intent(Intent.ACTION_SEND).apply {
                    setClassName(packageName, targetClass)
                    putExtra(Intent.EXTRA_SHORTCUT_ID, id)
                    putExtra("contactId", id)
                    putExtra("contactName", name)
                    type = "text/plain"
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TASK
                }

                // Create shortcut info builder
                val builder = ShortcutInfoCompat.Builder(context, id)
                    .setShortLabel(shortLabel)
                    .setLongLabel(name)
                    .setRank(index)  // Lower index = higher priority
                    .setIntent(intent)
                    .setLongLived(true)
                    .setCategories(setOf(TEXT_SHARE_CATEGORY))
                    .setPerson(createPerson(id, name, imageUrl))
                    .addCapabilityBinding(SEND_MESSAGE_CAPABILITY)

                // Create default icon
                val defaultIcon = createDefaultIcon(name)
                builder.setIcon(defaultIcon)

                builder.build()
            }

            // Remove existing shortcuts first 
            ShortcutManagerCompat.removeAllDynamicShortcuts(context)

            // Add new shortcuts
            return if (shortcuts.isNotEmpty()) {
                ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e("ExpoShareIntent", "Failed to publish direct share targets: ${e.message}")
            return false
        }
    }

    /**
     * Creates a Person object for shortcuts
     */
    private fun createPerson(
        id: String,
        name: String,
        imageUrl: String?
    ): androidx.core.app.Person {
        val builder = androidx.core.app.Person.Builder()
            .setName(name)
            .setKey(id)
            .setImportant(true)

        // Load image icon if available, otherwise use monogram
        if (!imageUrl.isNullOrEmpty()) {
            try {
                val uri = imageUrl.toUri()
                builder.setUri(uri.toString())

                // Try to set icon if possible (synchronously for simplicity)
                if (imageUrl.startsWith("file://") || imageUrl.startsWith("/")) {
                    val bitmap = BitmapFactory.decodeFile(
                        imageUrl.replace("file://", "")
                    )
                    if (bitmap != null) {
                        val adaptiveBitmap = createAdaptiveBitmap(bitmap)
                        val icon = IconCompat.createWithAdaptiveBitmap(adaptiveBitmap)
                        builder.setIcon(icon)
                    } else {
                        builder.setIcon(createDefaultIcon(name))
                    }
                } else {
                    builder.setIcon(createDefaultIcon(name))
                }
            } catch (e: Exception) {
                builder.setIcon(createDefaultIcon(name))
            }
        } else {
            builder.setIcon(createDefaultIcon(name))
        }

        return builder.build()
    }
}
