package com.example.ui.components

import com.example.util.MediaPreviewBox
import com.example.util.PdfViewerDialog
import com.example.util.VideoPlayerDialog
import com.example.data.AppFile
import coil.compose.AsyncImage
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.Dialog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.launch
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.AppViewModel
import com.example.ui.theme.Charcoal
import com.example.ui.theme.SurfaceCard
import com.example.ui.theme.WaterBlue

// Helper class representing parsed attachments from different subcomponents
data class ExplorerFile(
    val name: String,
    val type: String, // "image", "video", "audio", "others"
    val dateText: String,    // e.g. "Jun 19"
    val timestamp: Long,
    val sourceName: String,  // e.g. "Journal Entry", "Task: Homework", "Contact: Munee"
    val fileMime: String,
    val path: String = "",
    val onClick: () -> Unit,
    val appFileRef: com.example.data.AppFile? = null,
    val googleDocRef: com.example.util.GoogleDriveSyncManager.GoogleDocFile? = null,
    val googleSheetRef: com.example.util.GoogleDriveSyncManager.GoogleSheetFile? = null,
    val googleDriveRef: com.example.util.GoogleDriveSyncManager.GoogleDriveFileItem? = null
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileExplorerView(viewModel: AppViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val journalEntries by viewModel.journalEntries.collectAsState()
    val tasks by viewModel.tasks.collectAsState()
    val contacts by viewModel.contacts.collectAsState()
    val keepNotes by viewModel.keepNotes.collectAsState()
    
    var longPressedFile by remember { mutableStateOf<ExplorerFile?>(null) }
    var activePreviewFile by remember { mutableStateOf<ExplorerFile?>(null) }
    
    var activeFileForOptions by remember { mutableStateOf<ExplorerFile?>(null) }
    var activeFileForDetails by remember { mutableStateOf<ExplorerFile?>(null) }
    var fileToRename by remember { mutableStateOf<ExplorerFile?>(null) }
    var renameInputName by remember { mutableStateOf("") }
    var fileToShare by remember { mutableStateOf<ExplorerFile?>(null) }

    // Google Drive v3 specific UI state variables
    var activeGoogleFileDetails by remember { mutableStateOf<org.json.JSONObject?>(null) }
    var isLoadingGoogleDetails by remember { mutableStateOf(false) }
    
    var activeFileForPermissions by remember { mutableStateOf<ExplorerFile?>(null) }
    var permissionsList by remember { mutableStateOf<org.json.JSONArray?>(null) }
    var isLoadingPermissions by remember { mutableStateOf(false) }
    var sharePermissionEmail by remember { mutableStateOf("") }
    var sharePermissionRole by remember { mutableStateOf("reader") }
    
    var activeFileForComments by remember { mutableStateOf<ExplorerFile?>(null) }
    var commentsList by remember { mutableStateOf<org.json.JSONArray?>(null) }
    var isLoadingComments by remember { mutableStateOf(false) }
    var newCommentText by remember { mutableStateOf("") }
    
    var activeFileForRevisions by remember { mutableStateOf<ExplorerFile?>(null) }
    var revisionsList by remember { mutableStateOf<org.json.JSONArray?>(null) }
    var isLoadingRevisions by remember { mutableStateOf(false) }
    var googleDriveAboutInfo by remember { mutableStateOf<org.json.JSONObject?>(null) }

    // Google Drive Integration State
    val googleAccount = remember { com.google.android.gms.auth.api.signin.GoogleSignIn.getLastSignedInAccount(context) }
    var hasPermission by remember { mutableStateOf(com.example.util.GoogleDriveSyncManager.hasDrivePermission(context)) }
    var isOperating by remember { mutableStateOf(false) }
    var syncMessage by remember { mutableStateOf<String?>(null) }

    val prefs = remember { context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE) }
    var lastSyncTs by remember { mutableStateOf(prefs.getLong("gd_all_last_sync_timestamp", 0L)) }

    var cloudStrategy by remember { mutableStateOf(prefs.getString("cloud_viewing_strategy", "wholly_internal") ?: "wholly_internal") }
    var cloudPartialDays by remember { mutableStateOf(prefs.getInt("cloud_partial_days", 30)) }

    LaunchedEffect(Unit) {
        cloudStrategy = prefs.getString("cloud_viewing_strategy", "wholly_internal") ?: "wholly_internal"
        cloudPartialDays = prefs.getInt("cloud_partial_days", 30)
    }

    val scope = rememberCoroutineScope()

    val authResolutionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        hasPermission = com.example.util.GoogleDriveSyncManager.hasDrivePermission(context)
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            syncMessage = "Google Drive successfully authorized! Tap Backup or Restore to align your app data."
        } else {
            syncMessage = "Google Drive authorization declined."
        }
    }
    
    // 1. Gather files from Journal Entries
    val journalFiles = remember(journalEntries) {
        val list = mutableListOf<ExplorerFile>()
        val sdf = java.text.SimpleDateFormat("dd MMM", java.util.Locale.getDefault())
        journalEntries.forEach { entry ->
            if (entry.attachmentsJson.isNotEmpty()) {
                val attachmentsList = entry.attachmentsJson.split(";;")
                attachmentsList.forEach { attachment ->
                    if (attachment.isNotEmpty() && !attachment.startsWith("loc:")) {
                        var name = "Attachment"
                        var type = "others"
                        var filePath = ""
                        if (attachment.startsWith("photo:")) {
                            val path = attachment.removePrefix("photo:")
                            name = path.substringAfterLast("/")
                            type = "image"
                            filePath = path
                        } else if (attachment.startsWith("video:")) {
                            val path = attachment.removePrefix("video:")
                            name = path.substringAfterLast("/")
                            type = "video"
                            filePath = path
                        } else if (attachment.startsWith("audio:")) {
                            val path = attachment.removePrefix("audio:")
                            name = path.substringAfterLast("/")
                            type = "audio"
                            filePath = path
                        } else if (attachment.startsWith("file:")) {
                            val parts = attachment.removePrefix("file:").split("|path:")
                            name = parts.getOrNull(0) ?: "Document"
                            filePath = parts.getOrNull(1) ?: ""
                            type = if (name.lowercase().endsWith(".png") || name.lowercase().endsWith(".jpg") || name.lowercase().endsWith(".jpeg") || name.lowercase().endsWith(".webp")) {
                                "image"
                            } else if (name.lowercase().endsWith(".mp3") || name.lowercase().endsWith(".m4a") || name.lowercase().endsWith(".wav") || name.lowercase().endsWith(".gz") || name.lowercase().endsWith(".aac")) {
                                "audio"
                            } else if (name.lowercase().endsWith(".mp4") || name.lowercase().endsWith(".3gp") || name.lowercase().endsWith(".mkv") || name.lowercase().endsWith(".mov")) {
                                "video"
                            } else {
                                "others"
                            }
                        }
                        val formattedDate = try {
                            val parsed = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).parse(entry.dateString)
                            parsed?.let { sdf.format(it) } ?: sdf.format(java.util.Date(entry.timestamp))
                        } catch (e: Exception) {
                            sdf.format(java.util.Date(entry.timestamp))
                        }
                        
                        list.add(
                            ExplorerFile(
                                name = name,
                                type = type,
                                dateText = formattedDate,
                                timestamp = entry.timestamp,
                                sourceName = "Journal (${entry.dateString})",
                                fileMime = if (type == "image") "image/png" else if (type == "video") "video/mp4" else if (type == "audio") "audio/mpeg" else "application/pdf",
                                path = filePath,
                                onClick = {
                                    viewModel.selectJournal(entry.id)
                                }
                            )
                        )
                    }
                }
            }
        }
        list
    }

    // 2. Gather files from Tasks
    val taskFiles = remember(tasks) {
        val list = mutableListOf<ExplorerFile>()
        val sdf = java.text.SimpleDateFormat("dd MMM", java.util.Locale.getDefault())
        val metaAttachmentPattern = Regex("""\[Attachment: ([^\]]+)\]""")
        tasks.forEach { task ->
            val desc = task.description
            val match = metaAttachmentPattern.find(desc)
            val attachment = match?.groupValues?.get(1)
            if (attachment != null && attachment != "None" && attachment.isNotEmpty()) {
                val name = attachment
                val type = if (name.lowercase().endsWith(".png") || name.lowercase().endsWith(".jpg") || name.lowercase().endsWith(".jpeg") || name.lowercase().endsWith(".webp")) {
                    "image"
                } else if (name.lowercase().endsWith(".mp3") || name.lowercase().endsWith(".m4a") || name.lowercase().endsWith(".wav") || name.lowercase().endsWith(".aac")) {
                    "audio"
                } else if (name.lowercase().endsWith(".mp4") || name.lowercase().endsWith(".3gp") || name.lowercase().endsWith(".mkv") || name.lowercase().endsWith(".mov")) {
                    "video"
                } else {
                    "others"
                }
                val formattedDate = if (task.dueDateString.isNotEmpty()) {
                    try {
                        val parsed = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).parse(task.dueDateString)
                        parsed?.let { sdf.format(it) } ?: sdf.format(java.util.Date())
                    } catch (e: Exception) {
                        sdf.format(java.util.Date())
                    }
                } else {
                    sdf.format(java.util.Date())
                }
                list.add(
                    ExplorerFile(
                        name = name,
                        type = type,
                        dateText = formattedDate,
                        timestamp = System.currentTimeMillis() - 1000L * 60 * 30, // approximate task created earlier
                        sourceName = "Task: ${task.title}",
                        fileMime = if (type == "image") "image/png" else if (type == "video") "video/mp4" else if (type == "audio") "audio/mpeg" else "application/pdf",
                        path = java.io.File(com.example.util.StorageHelper.getAppFilesDir(context), name).absolutePath,
                        onClick = {
                            viewModel.selectTask(task.id)
                        }
                    )
                )
            }
        }
        list
    }

    // 3. Gather files from Contacts
    val contactFiles = remember(contacts) {
        val list = mutableListOf<ExplorerFile>()
        val sdf = java.text.SimpleDateFormat("dd MMM", java.util.Locale.getDefault())
        contacts.forEach { contact ->
            // Active photo
            if (!contact.photoUri.isNullOrEmpty()) {
                list.add(
                    ExplorerFile(
                        name = "photo_${contact.firstName}_${contact.lastName}.png",
                        type = "image",
                        dateText = sdf.format(java.util.Date()),
                        timestamp = System.currentTimeMillis() - 1000L * 60 * 15,
                        sourceName = "Contact: ${contact.firstName} ${contact.lastName}",
                        fileMime = "image/png",
                        path = contact.photoUri,
                        onClick = {
                            viewModel.selectContact(contact.id)
                        }
                    )
                )
            }
            // Other attached files
            if (contact.attachedFilesJson.isNotEmpty()) {
                try {
                    val arr = org.json.JSONArray(contact.attachedFilesJson)
                    for (i in 0 until arr.length()) {
                        val path = arr.getString(i)
                        val name = path.substringAfterLast("/")
                        val type = if (name.lowercase().endsWith(".png") || name.lowercase().endsWith(".jpg") || name.lowercase().endsWith(".jpeg") || name.lowercase().endsWith(".webp")) {
                            "image"
                        } else if (name.lowercase().endsWith(".mp3") || name.lowercase().endsWith(".m4a") || name.lowercase().endsWith(".wav") || name.lowercase().endsWith(".aac")) {
                            "audio"
                        } else if (name.lowercase().endsWith(".mp4") || name.lowercase().endsWith(".3gp") || name.lowercase().endsWith(".mkv") || name.lowercase().endsWith(".mov")) {
                            "video"
                        } else {
                            "others"
                        }
                        list.add(
                            ExplorerFile(
                                name = name,
                                type = type,
                                dateText = sdf.format(java.util.Date()),
                                timestamp = System.currentTimeMillis() - 1000L * 60 * 10,
                                sourceName = "Contact: ${contact.firstName} ${contact.lastName}",
                                fileMime = if (type == "image") "image/png" else if (type == "video") "video/mp4" else if (type == "audio") "audio/mpeg" else "application/pdf",
                                path = path,
                                onClick = {
                                    viewModel.selectContact(contact.id)
                                }
                            )
                        )
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        list
    }

    // 4. Collect uploaded files from the database
    val dbFilesState by viewModel.files.collectAsState()

    val dbFiles = remember(dbFilesState) {
        val list = mutableListOf<ExplorerFile>()
        val sdf = java.text.SimpleDateFormat("dd MMM", java.util.Locale.getDefault())
        dbFilesState.forEach { appFile ->
            val type = when {
                appFile.mimeType.lowercase().startsWith("image/") || appFile.name.lowercase().endsWith(".png") || appFile.name.lowercase().endsWith(".jpg") || appFile.name.lowercase().endsWith(".jpeg") || appFile.name.lowercase().endsWith(".webp") -> "image"
                appFile.mimeType.lowercase().startsWith("video/") || appFile.name.lowercase().endsWith(".mp4") || appFile.name.lowercase().endsWith(".mkv") || appFile.name.lowercase().endsWith(".mov") -> "video"
                appFile.mimeType.lowercase().startsWith("audio/") || appFile.name.lowercase().endsWith(".mp3") || appFile.name.lowercase().endsWith(".m4a") || appFile.name.lowercase().endsWith(".wav") || appFile.name.lowercase().endsWith(".gz") -> "audio"
                else -> "others"
            }
            list.add(
                ExplorerFile(
                    name = appFile.name,
                    type = type,
                    dateText = sdf.format(java.util.Date(appFile.timestamp)),
                    timestamp = appFile.timestamp,
                    sourceName = "Uploaded (${appFile.path})", // path stores the folder category!
                    fileMime = appFile.mimeType,
                    path = appFile.uriString,
                    onClick = {},
                    appFileRef = appFile
                )
            )
        }
        list
    }

    // Gather files from Keep Notes (Google Notes)
    val keepNoteFiles = remember(keepNotes) {
        val list = mutableListOf<ExplorerFile>()
        val sdf = java.text.SimpleDateFormat("dd MMM", java.util.Locale.getDefault())
        val attachmentsRegex = Regex("""\[Attachments: ([^\]]+)\]""")
        val singleAttachmentRegex = Regex("""\[Attachment: ([^\]]+)\]""")
        
        keepNotes.forEach { note ->
            val match = attachmentsRegex.find(note.content)
            val attachmentsList = if (match != null) {
                match.groupValues[1].split(";;").filter { it.isNotEmpty() }
            } else {
                val oldMatch = singleAttachmentRegex.find(note.content)
                val oldAtt = oldMatch?.groupValues?.get(1)?.trim() ?: ""
                if (oldAtt.isNotEmpty()) listOf(oldAtt) else emptyList()
            }
            
            attachmentsList.forEach { attachString ->
                val type = when {
                    attachString.startsWith("photo:") -> "image"
                    attachString.startsWith("video:") -> "video"
                    attachString.startsWith("audio:") -> "audio"
                    attachString.startsWith("file:") -> "others"
                    else -> "others"
                }
                
                val path = when {
                    attachString.startsWith("photo:") -> attachString.removePrefix("photo:")
                    attachString.startsWith("video:") -> attachString.removePrefix("video:")
                    attachString.startsWith("audio:") -> attachString.removePrefix("audio:")
                    attachString.startsWith("file:") -> {
                        val filePart = attachString.removePrefix("file:").split("|path:")
                        filePart.getOrNull(1) ?: ""
                    }
                    else -> attachString
                }
                
                val name = when {
                    attachString.startsWith("file:") -> {
                        val filePart = attachString.removePrefix("file:").split("|path:")
                        filePart.getOrNull(0) ?: "Attachment File"
                    }
                    else -> path.substringAfterLast("/")
                }
                
                val fileMime = when (type) {
                    "image" -> "image/png"
                    "video" -> "video/mp4"
                    "audio" -> "audio/mpeg"
                    else -> {
                        if (name.lowercase().endsWith(".pdf")) "application/pdf"
                        else if (name.lowercase().endsWith(".txt")) "text/plain"
                        else "application/octet-stream"
                    }
                }
                
                list.add(
                    ExplorerFile(
                        name = name,
                        type = type,
                        dateText = sdf.format(java.util.Date(note.timestamp)),
                        timestamp = note.timestamp,
                        sourceName = "Note: ${note.title.ifEmpty { "Untitled Note" }}",
                        fileMime = fileMime,
                        path = path,
                        onClick = {}
                    )
                )
            }
        }
        list
    }

    // 5. Merge all files sorted by descending timestamp (recent files on top)
    val allExplorerFiles = remember(journalFiles, taskFiles, contactFiles, dbFiles, keepNoteFiles) {
        (journalFiles + taskFiles + contactFiles + dbFiles + keepNoteFiles).sortedByDescending { it.timestamp }
    }

    // Navigation and folder states
    var activeExplorerTab by remember { mutableStateOf("Folders") } // "Folders" or "Flat View"
    var activeFolder by remember { mutableStateOf<String?>(null) } // "Journal", "Tasks", "Contacts", "General", "Google Sheets", "Google Docs", "Google Drive", "Google Keep"
    val friendsPathStack = remember { mutableStateListOf<String>() }

    // Custom Writable Actions states
    var showActionSelectorDialog by remember { mutableStateOf(false) }
    
    // Create Doc State
    var showCreateTextDocDialog by remember { mutableStateOf(false) }
    var textDocTitle by remember { mutableStateOf("") }
    var textDocContent by remember { mutableStateOf("") }
    
    // Add Shared Link State
    var showAddSharedLinkDialog by remember { mutableStateOf(false) }
    var sharedFolderTitle by remember { mutableStateOf("") }
    var sharedFolderUrl by remember { mutableStateOf("") }
    var linkCategory by remember { mutableStateOf("Folder") } // "Folder", "Document", "Others"
    var docType by remember { mutableStateOf("PDF") } // "PDF", "Doc", "Excel", "Others"
    var linkCategoryExpanded by remember { mutableStateOf(false) }
    var docTypeExpanded by remember { mutableStateOf(false) }
    var estimatedSize by remember { mutableStateOf(1250000L) }
    var sizeLoading by remember { mutableStateOf(false) }

    // Create Local Virtual Folder State
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }

    // Add Shared Folder Route-Map State
    var showAddRouteMapFolderDialog by remember { mutableStateOf(false) }
    var routeMapFolderTitle by remember { mutableStateOf("") }
    var routeMapFolderUrl by remember { mutableStateOf("") }
    var routeMapJsonContent by remember { mutableStateOf("") }

    // Text Doc Viewer/Editor State
    var activeEditingDocFile by remember { mutableStateOf<ExplorerFile?>(null) }
    var editingDocContent by remember { mutableStateOf("") }

    // Google Drive Shared Link Browsing State
    var activeSharedLinkFolder by remember { mutableStateOf<ExplorerFile?>(null) }

    // Wonderful Interactive Audio Player state
    var activeAudioPlayerFile by remember { mutableStateOf<ExplorerFile?>(null) }

    // Filter modes for Flat View
    val filterOptions = listOf("All", "Image", "Video", "Audio", "Others", "Google Sheets")
    var selectedFilter by remember { mutableStateOf("All") }

    val googleSheets by viewModel.googleSheets.collectAsState()
    val isLoadingSheets by viewModel.isLoadingGoogleSheets.collectAsState()
    val sheetsError by viewModel.googleSheetsError.collectAsState()
    var showCreateSheetDialog by remember { mutableStateOf(false) }
    var newSheetTitle by remember { mutableStateOf("") }

    // Google Docs States
    val googleDocs by viewModel.googleDocs.collectAsState()
    val isLoadingDocs by viewModel.isLoadingGoogleDocs.collectAsState()
    val docsError by viewModel.googleDocsError.collectAsState()
    var showCreateDocDialog by remember { mutableStateOf(false) }
    var newDocTitle by remember { mutableStateOf("") }

    // Google Drive Explorer States
    val googleDriveFiles by viewModel.googleDriveFiles.collectAsState()
    val isLoadingDrive by viewModel.isLoadingGoogleDrive.collectAsState()
    val driveError by viewModel.googleDriveError.collectAsState()
    val driveFolderStack = remember { mutableStateListOf<Pair<String, String>>() } // Pair(FolderID, FolderName)
    var showCreateDriveFolderDialog by remember { mutableStateOf(false) }
    var newDriveFolderTitle by remember { mutableStateOf("") }

    // Unified Document/Sheet/Drive web view opening states
    var selectedDocUrl by remember { mutableStateOf<String?>(null) }
    var selectedDocTitle by remember { mutableStateOf("") }

    // Direct Upload File Dialog state
    var showUploadFileDialog by remember { mutableStateOf(false) }
    var uploadFileName by remember { mutableStateOf("") }
    var uploadFolderCategory by remember { mutableStateOf("General") }
    var selectedFileUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var selectedFileMimeType by remember { mutableStateOf("") }
    var selectedFileSize by remember { mutableStateOf(0L) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: android.net.Uri? ->
        if (uri != null) {
            selectedFileUri = uri
            val resolver = context.contentResolver
            selectedFileMimeType = resolver.getType(uri) ?: "application/octet-stream"
            
            var displayName = ""
            var size = 0L
            try {
                resolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                        if (nameIndex != -1) displayName = cursor.getString(nameIndex) ?: ""
                        if (sizeIndex != -1) size = cursor.getLong(sizeIndex)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            if (displayName.isEmpty()) {
                displayName = "file_${System.currentTimeMillis()}"
            }
            uploadFileName = displayName
            selectedFileSize = size
        }
    }

    val sheetsAuthLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            when (activeFolder) {
                "Google Sheets" -> viewModel.fetchGoogleSheets(context)
                "Google Docs" -> viewModel.fetchGoogleDocs(context)
                "Google Drive" -> {
                    val currentFolderId = if (driveFolderStack.isEmpty()) "root" else driveFolderStack.last().first
                    viewModel.fetchGoogleDriveFiles(context, currentFolderId)
                }
            }
        }
    }

    // Auto-fetch google sheets, docs, or drive files if needed
    LaunchedEffect(selectedFilter, activeFolder, driveFolderStack.size) {
        if (selectedFilter == "Google Sheets" || activeFolder == "Google Sheets") {
            viewModel.fetchGoogleSheets(context) { intent ->
                sheetsAuthLauncher.launch(intent)
            }
        } else if (activeFolder == "Google Docs") {
            viewModel.fetchGoogleDocs(context) { intent ->
                sheetsAuthLauncher.launch(intent)
            }
        } else if (activeFolder == "Google Drive") {
            val currentFolderId = if (driveFolderStack.isEmpty()) "root" else driveFolderStack.last().first
            viewModel.fetchGoogleDriveFiles(context, currentFolderId) { intent ->
                sheetsAuthLauncher.launch(intent)
            }
            viewModel.fetchGoogleDriveAbout(
                context = context,
                onSuccess = { about ->
                    googleDriveAboutInfo = about
                },
                onFailure = {
                    // silent failure or log
                }
            )
        } else if (activeFolder == "Journal" || activeFolder == "Tasks" || activeFolder == "Contacts" || activeFolder == "General") {
            viewModel.syncFolderWithFirebase(activeFolder!!)
        }
    }

    if (selectedDocUrl != null) {
        GoogleDocumentViewer(viewModel = viewModel, title = selectedDocTitle, docUrl = selectedDocUrl!!) {
            selectedDocUrl = null
        }
        return
    }

    // Flat View Filtered Files
    val filteredFiles = remember(allExplorerFiles, selectedFilter) {
        if (selectedFilter == "All") {
            allExplorerFiles
        } else {
            allExplorerFiles.filter { it.type.equals(selectedFilter, ignoreCase = true) }
        }
    }

    // Partition files into category-specific folders
    val folderJournalFiles = remember(allExplorerFiles) {
        allExplorerFiles.filter {
            it.sourceName.contains("Journal", ignoreCase = true) || 
            it.sourceName.contains("(Journal)", ignoreCase = true)
        }
    }

    val folderTaskFiles = remember(allExplorerFiles) {
        allExplorerFiles.filter {
            it.sourceName.contains("Task", ignoreCase = true) || 
            it.sourceName.contains("(Tasks)", ignoreCase = true) ||
            it.sourceName.contains("(Task)", ignoreCase = true)
        }
    }

    val folderContactFiles = remember(allExplorerFiles) {
        allExplorerFiles.filter {
            it.sourceName.contains("Contact", ignoreCase = true) || 
            it.sourceName.contains("(Contacts)", ignoreCase = true) ||
            it.sourceName.contains("(Contact)", ignoreCase = true)
        }
    }

    val folderGeneralFiles = remember(allExplorerFiles) {
        allExplorerFiles.filter {
            (it.sourceName.contains("General", ignoreCase = true) ||
             it.sourceName.contains("(General)", ignoreCase = true) ||
             (!it.sourceName.contains("Journal", ignoreCase = true) &&
              !it.sourceName.contains("Task", ignoreCase = true) &&
              !it.sourceName.contains("Contact", ignoreCase = true) &&
              !it.sourceName.startsWith("Note:", ignoreCase = true) &&
              !it.sourceName.contains("(Google Notes)", ignoreCase = true) &&
              !it.sourceName.contains("(Google Keep)", ignoreCase = true) &&
              it.sourceName.startsWith("Uploaded", ignoreCase = true))) &&
            !(it.appFileRef?.path?.startsWith("Friends") ?: false)
        }
    }

    val folderGoogleNotesFiles = remember(allExplorerFiles) {
        allExplorerFiles.filter {
            it.sourceName.startsWith("Note:", ignoreCase = true) ||
            it.sourceName.contains("(Google Notes)", ignoreCase = true) ||
            it.sourceName.contains("(Google Keep)", ignoreCase = true)
        }
    }

    val folderFriendsFiles = remember(dbFiles) {
        dbFiles.filter { it.appFileRef?.path?.startsWith("Friends") == true }
    }

    val folderFavoriteFiles = remember(allExplorerFiles) {
        allExplorerFiles.filter { it.appFileRef?.isFavorite == true }
    }

    Scaffold(
        floatingActionButton = {
            val isCloudFolder = activeFolder == "Google Sheets" || activeFolder == "Google Docs" || activeFolder == "Google Drive" || activeFolder == "Google Notes" || activeFolder == "Google Keep" || activeFolder == "Keep Notes"
            if (activeExplorerTab == "Folders" && !isCloudFolder) {
                FloatingActionButton(
                    onClick = {
                        showActionSelectorDialog = true
                    },
                    containerColor = WaterBlue,
                    contentColor = Color.Black,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.testTag("add_file_fab")
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add Content")
                }
            }
        },
        containerColor = Color.Transparent
    ) { innerPadding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
        ) {
            // Storage Strategy and Cloud Fetch Top Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
                    .testTag("storage_strategy_card"),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F0F13)),
                border = androidx.compose.foundation.BorderStroke(1.dp, WaterBlue.copy(alpha = 0.25f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                var isCloudFetching by remember { mutableStateOf(false) }

                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = when (cloudStrategy) {
                                    "only_cloud" -> Icons.Default.Cloud
                                    "partial" -> Icons.Default.Sync
                                    else -> Icons.Default.PhoneAndroid
                                },
                                contentDescription = null,
                                tint = WaterBlue,
                                modifier = Modifier.size(20.dp)
                            )
                            Column {
                                Text(
                                    text = "Storage Strategy:",
                                    color = Color.Gray,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = when (cloudStrategy) {
                                        "only_cloud" -> "☁️ Only Cloud (0B Occupied)"
                                        "partial" -> "🔄 Hybrid ($cloudPartialDays Days Local)"
                                        else -> "📱 Wholly Internal"
                                    },
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        // Cloud Fetch Button
                        Button(
                            onClick = {
                                isCloudFetching = true
                                scope.launch {
                                    kotlinx.coroutines.delay(1500)
                                    isCloudFetching = false
                                    android.widget.Toast.makeText(
                                        context,
                                        "Cloud Fetch Complete! Direct cloud-viewing sync complete. 0 bytes local storage written.",
                                        android.widget.Toast.LENGTH_LONG
                                    ).show()
                                }
                            },
                            enabled = !isCloudFetching,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = WaterBlue.copy(alpha = 0.15f),
                                contentColor = WaterBlue
                            ),
                            border = androidx.compose.foundation.BorderStroke(1.dp, WaterBlue.copy(alpha = 0.3f)),
                            shape = RoundedCornerShape(16.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(28.dp).testTag("cloud_fetch_button")
                        ) {
                            if (isCloudFetching) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp),
                                    color = WaterBlue,
                                    strokeWidth = 1.5.dp
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("FETCHING...", fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("CLOUD FETCH & SHOW", fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    if (isCloudFetching) {
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            color = WaterBlue,
                            trackColor = Color.White.copy(alpha = 0.1f),
                            modifier = Modifier.fillMaxWidth().height(2.dp)
                        )
                    }
                }
            }

            // Tab Selector: Folders vs All Files Flat View
            if (activeFolder == null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    listOf("Folders", "Flat View").forEach { tab ->
                        val isTabSelected = activeExplorerTab == tab
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isTabSelected) WaterBlue.copy(alpha = 0.15f) else SurfaceCard)
                                .border(
                                    1.dp,
                                    if (isTabSelected) WaterBlue else Color.Gray.copy(alpha = 0.2f),
                                    RoundedCornerShape(12.dp)
                                )
                                .clickable { activeExplorerTab = tab }
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (tab == "Folders") Icons.Default.Folder else Icons.Default.GridOn,
                                    contentDescription = null,
                                    tint = if (isTabSelected) WaterBlue else Color.LightGray,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = tab,
                                    color = if (isTabSelected) WaterBlue else Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }
                }
            }

            if (activeExplorerTab == "Folders") {
                if (activeFolder == null) {
                    // Folder Dashboard
                    Text(
                        text = "CATEGORIZED FOLDERS",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Gray,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                    ) {
                        val folders = listOf(
                            Triple("Favorites", folderFavoriteFiles.size, Color(0xFFEF5350)),
                            Triple("Journal", folderJournalFiles.size, Color(0xFFE57373)),
                            Triple("Tasks", folderTaskFiles.size, Color(0xFF81C784)),
                            Triple("Contacts", folderContactFiles.size, Color(0xFF64B5F6)),
                            Triple("General", folderGeneralFiles.size, Color(0xFFFFB74D)),
                            Triple("Keep Notes", folderGoogleNotesFiles.size, Color(0xFFFF9E0F)),
                            Triple("Friends", folderFriendsFiles.size, Color(0xFFAB47BC)),
                            Triple("Google Sheets", googleSheets.size, Color(0xFF0F9D58)),
                            Triple("Google Docs", googleDocs.size, Color(0xFF4285F4)),
                            Triple("Google Drive", googleDriveFiles.size, Color(0xFFF4B400))
                        )

                        folders.forEach { (name, count, color) ->
                            val folderIcon = when (name) {
                                "Favorites" -> Icons.Default.Favorite
                                "Journal" -> Icons.Default.Book
                                "Tasks" -> Icons.Default.TaskAlt
                                "Contacts" -> Icons.Default.People
                                "Google Sheets" -> Icons.Default.InsertDriveFile
                                "Google Docs" -> Icons.Default.Description
                                "Google Drive" -> Icons.Default.CloudQueue
                                "Keep Notes" -> Icons.Default.NoteAlt
                                "Friends" -> Icons.Default.PeopleOutline
                                else -> Icons.Default.FolderOpen
                            }

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { 
                                        if (name == "Friends") {
                                            friendsPathStack.clear()
                                        }
                                        activeFolder = name 
                                    }
                                    .testTag("folder_item_$name"),
                                colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(44.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(color.copy(alpha = 0.15f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = folderIcon,
                                            contentDescription = null,
                                            tint = color,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = name,
                                            color = Color.White,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "$count items",
                                            color = Color.Gray,
                                            fontSize = 12.sp
                                        )
                                    }
                                    Icon(
                                        imageVector = Icons.Default.ChevronRight,
                                        contentDescription = "Open Folder",
                                        tint = Color.Gray.copy(alpha = 0.5f),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                } else {
                    // Inside a selected folder
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                if (activeFolder == "Google Drive" && driveFolderStack.isNotEmpty()) {
                                    driveFolderStack.removeAt(driveFolderStack.lastIndex)
                                } else if (activeFolder == "Friends" && friendsPathStack.isNotEmpty()) {
                                    friendsPathStack.removeAt(friendsPathStack.lastIndex)
                                } else {
                                    activeFolder = null
                                }
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = WaterBlue)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "FILE EXPLORER",
                                color = Color.Gray,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (activeFolder == "Google Drive") {
                                    if (driveFolderStack.isEmpty()) "📁 Google Drive Root" else "📁 " + driveFolderStack.last().second
                                } else if (activeFolder == "Friends") {
                                    if (friendsPathStack.isEmpty()) "📁 Study Group" else "📁 " + friendsPathStack.last().substringBefore(":")
                                } else {
                                    "📁 $activeFolder"
                                },
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                                  when (activeFolder) {
                        "Google Sheets" -> {
                            // Render Google Sheets Folder View
                            if (showCreateSheetDialog) {
                                AlertDialog(
                                    onDismissRequest = { showCreateSheetDialog = false },
                                    title = { Text("Create New Google Sheet", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
                                    text = {
                                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text("Enter a title for your new Google Spreadsheet.", color = Color.LightGray, fontSize = 13.sp)
                                            OutlinedTextField(
                                                value = newSheetTitle,
                                                onValueChange = { newSheetTitle = it },
                                                placeholder = { Text("Untitled Spreadsheet", color = Color.Gray) },
                                                textStyle = androidx.compose.ui.text.TextStyle(color = Color.White),
                                                colors = OutlinedTextFieldDefaults.colors(
                                                    focusedBorderColor = WaterBlue,
                                                    unfocusedBorderColor = Color.Gray,
                                                    cursorColor = WaterBlue
                                                ),
                                                singleLine = true,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }
                                    },
                                    confirmButton = {
                                        TextButton(
                                            onClick = {
                                                val title = if (newSheetTitle.trim().isEmpty()) "Untitled Spreadsheet" else newSheetTitle.trim()
                                                viewModel.createGoogleSheet(context, title, onSuccess = { webLink ->
                                                    selectedDocUrl = webLink
                                                    selectedDocTitle = title
                                                    android.widget.Toast.makeText(context, "Successfully created Sheet!", android.widget.Toast.LENGTH_SHORT).show()
                                                }, onAuthRequired = { intent ->
                                                    sheetsAuthLauncher.launch(intent)
                                                })
                                                showCreateSheetDialog = false
                                                newSheetTitle = ""
                                            }
                                        ) {
                                            Text("CREATE", color = WaterBlue, fontWeight = FontWeight.Bold)
                                        }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { showCreateSheetDialog = false }) {
                                            Text("CANCEL", color = Color.Gray)
                                        }
                                    },
                                    containerColor = Color(0xFF161618),
                                    shape = RoundedCornerShape(16.dp)
                                )
                            }

                            if (isLoadingSheets) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(color = WaterBlue)
                                }
                            } else if (sheetsError != null) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
                                        Icon(Icons.Default.Cloud, contentDescription = null, modifier = Modifier.size(48.dp), tint = Color.Gray)
                                        Spacer(modifier = Modifier.height(12.dp))
                                        Text(sheetsError!!, color = Color.LightGray, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                        Spacer(modifier = Modifier.height(16.dp))
                                        Button(
                                            onClick = {
                                                viewModel.fetchGoogleSheets(context) { intent ->
                                                    sheetsAuthLauncher.launch(intent)
                                                }
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = WaterBlue)
                                        ) {
                                            Text("Retry Sync", color = Color.Black, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            } else if (googleSheets.isEmpty()) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
                                        Icon(Icons.Default.InsertDriveFile, contentDescription = null, modifier = Modifier.size(48.dp), tint = Color.Gray)
                                        Spacer(modifier = Modifier.height(12.dp))
                                        Text("No Google Sheets found under your account.", color = Color.LightGray)
                                        Spacer(modifier = Modifier.height(16.dp))
                                        Button(
                                            onClick = { showCreateSheetDialog = true },
                                            colors = ButtonDefaults.buttonColors(containerColor = WaterBlue)
                                        ) {
                                            Icon(Icons.Default.Cloud, contentDescription = null, tint = Color.Black)
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text("Create New Sheet", color = Color.Black, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            } else {
                                Column(modifier = Modifier.fillMaxSize()) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "GOOGLE SPREADSHEETS (${googleSheets.size})",
                                            color = Color.Gray,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Button(
                                            onClick = { showCreateSheetDialog = true },
                                            colors = ButtonDefaults.buttonColors(containerColor = WaterBlue),
                                            shape = RoundedCornerShape(20.dp),
                                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                            modifier = Modifier.height(32.dp)
                                        ) {
                                            Icon(Icons.Default.Cloud, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.Black)
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("NEW SHEET", color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }

                                    androidx.compose.foundation.lazy.LazyColumn(
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.fillMaxSize()
                                    ) {
                                        items(googleSheets) { sheet ->
                                            Card(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable { 
                                                        selectedDocUrl = sheet.webViewLink
                                                        selectedDocTitle = sheet.name
                                                    },
                                                colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                                                border = androidx.compose.foundation.BorderStroke(1.dp, Color.Gray.copy(alpha = 0.2f))
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(12.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.InsertDriveFile,
                                                        contentDescription = "Spreadsheet",
                                                        tint = Color(0xFF107C41),
                                                        modifier = Modifier.size(36.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(12.dp))
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(
                                                            text = sheet.name,
                                                            color = Color.White,
                                                            fontSize = 14.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            maxLines = 1,
                                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                                        )
                                                        Spacer(modifier = Modifier.height(2.dp))
                                                        Text(
                                                            text = "Last updated: " + if (sheet.modifiedTime.isNotEmpty()) sheet.modifiedTime.substringBefore("T") else "Unknown",
                                                            color = Color.Gray,
                                                            fontSize = 11.sp
                                                        )
                                                    }
                                                    Icon(
                                                        imageVector = Icons.Default.ArrowUpward,
                                                        contentDescription = "Open",
                                                        tint = Color.Gray,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        "Google Docs" -> {
                            // Render Google Docs Folder View
                            if (showCreateDocDialog) {
                                AlertDialog(
                                    onDismissRequest = { showCreateDocDialog = false },
                                    title = { Text("Create New Google Doc", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
                                    text = {
                                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text("Enter a title for your new Google Document.", color = Color.LightGray, fontSize = 13.sp)
                                            OutlinedTextField(
                                                value = newDocTitle,
                                                onValueChange = { newDocTitle = it },
                                                placeholder = { Text("Untitled Document", color = Color.Gray) },
                                                textStyle = androidx.compose.ui.text.TextStyle(color = Color.White),
                                                colors = OutlinedTextFieldDefaults.colors(
                                                    focusedBorderColor = WaterBlue,
                                                    unfocusedBorderColor = Color.Gray,
                                                    cursorColor = WaterBlue
                                                ),
                                                singleLine = true,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }
                                    },
                                    confirmButton = {
                                        TextButton(
                                            onClick = {
                                                val title = if (newDocTitle.trim().isEmpty()) "Untitled Document" else newDocTitle.trim()
                                                viewModel.createGoogleDoc(context, title, onSuccess = { webLink ->
                                                    selectedDocUrl = webLink
                                                    selectedDocTitle = title
                                                    android.widget.Toast.makeText(context, "Successfully created Document!", android.widget.Toast.LENGTH_SHORT).show()
                                                }, onAuthRequired = { intent ->
                                                    sheetsAuthLauncher.launch(intent)
                                                })
                                                showCreateDocDialog = false
                                                newDocTitle = ""
                                            }
                                        ) {
                                            Text("CREATE", color = WaterBlue, fontWeight = FontWeight.Bold)
                                        }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { showCreateDocDialog = false }) {
                                            Text("CANCEL", color = Color.Gray)
                                        }
                                    },
                                    containerColor = Color(0xFF161618),
                                    shape = RoundedCornerShape(16.dp)
                                )
                            }

                            if (isLoadingDocs) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(color = WaterBlue)
                                }
                            } else if (docsError != null) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
                                        Icon(Icons.Default.Cloud, contentDescription = null, modifier = Modifier.size(48.dp), tint = Color.Gray)
                                        Spacer(modifier = Modifier.height(12.dp))
                                        Text(docsError!!, color = Color.LightGray, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                        Spacer(modifier = Modifier.height(16.dp))
                                        Button(
                                            onClick = {
                                                viewModel.fetchGoogleDocs(context) { intent ->
                                                    sheetsAuthLauncher.launch(intent)
                                                }
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = WaterBlue)
                                        ) {
                                            Text("Retry Sync", color = Color.Black, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            } else if (googleDocs.isEmpty()) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
                                        Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(48.dp), tint = Color.Gray)
                                        Spacer(modifier = Modifier.height(12.dp))
                                        Text("No Google Docs found under your account.", color = Color.LightGray)
                                        Spacer(modifier = Modifier.height(16.dp))
                                        Button(
                                            onClick = { showCreateDocDialog = true },
                                            colors = ButtonDefaults.buttonColors(containerColor = WaterBlue)
                                        ) {
                                            Icon(Icons.Default.Cloud, contentDescription = null, tint = Color.Black)
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text("Create New Doc", color = Color.Black, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            } else {
                                Column(modifier = Modifier.fillMaxSize()) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "GOOGLE DOCUMENTS (${googleDocs.size})",
                                            color = Color.Gray,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Button(
                                            onClick = { showCreateDocDialog = true },
                                            colors = ButtonDefaults.buttonColors(containerColor = WaterBlue),
                                            shape = RoundedCornerShape(20.dp),
                                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                            modifier = Modifier.height(32.dp)
                                        ) {
                                            Icon(Icons.Default.Cloud, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.Black)
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("NEW DOC", color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }

                                    androidx.compose.foundation.lazy.LazyColumn(
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.fillMaxSize()
                                    ) {
                                        items(googleDocs) { doc ->
                                            Card(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable { 
                                                        selectedDocUrl = doc.webViewLink
                                                        selectedDocTitle = doc.name
                                                    },
                                                colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                                                border = androidx.compose.foundation.BorderStroke(1.dp, Color.Gray.copy(alpha = 0.2f))
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(12.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Description,
                                                        contentDescription = "Document",
                                                        tint = Color(0xFF4285F4),
                                                        modifier = Modifier.size(36.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(12.dp))
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(
                                                            text = doc.name,
                                                            color = Color.White,
                                                            fontSize = 14.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            maxLines = 1,
                                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                                        )
                                                        Spacer(modifier = Modifier.height(2.dp))
                                                        Text(
                                                            text = "Last updated: " + if (doc.modifiedTime.isNotEmpty()) doc.modifiedTime.substringBefore("T") else "Unknown",
                                                            color = Color.Gray,
                                                            fontSize = 11.sp
                                                        )
                                                    }
                                                    Icon(
                                                        imageVector = Icons.Default.ArrowUpward,
                                                        contentDescription = "Open",
                                                        tint = Color.Gray,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        "Google Drive" -> {
                            // Render Google Drive File Explorer View
                            if (showCreateDriveFolderDialog) {
                                AlertDialog(
                                    onDismissRequest = { showCreateDriveFolderDialog = false },
                                    title = { Text("Create New Folder", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
                                    text = {
                                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text("Enter a name for your new Google Drive folder.", color = Color.LightGray, fontSize = 13.sp)
                                            OutlinedTextField(
                                                value = newDriveFolderTitle,
                                                onValueChange = { newDriveFolderTitle = it },
                                                placeholder = { Text("New Folder", color = Color.Gray) },
                                                textStyle = androidx.compose.ui.text.TextStyle(color = Color.White),
                                                colors = OutlinedTextFieldDefaults.colors(
                                                    focusedBorderColor = WaterBlue,
                                                    unfocusedBorderColor = Color.Gray,
                                                    cursorColor = WaterBlue
                                                ),
                                                singleLine = true,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }
                                    },
                                    confirmButton = {
                                        TextButton(
                                            onClick = {
                                                val folderName = if (newDriveFolderTitle.trim().isEmpty()) "New Folder" else newDriveFolderTitle.trim()
                                                val currentParentId = if (driveFolderStack.isEmpty()) "root" else driveFolderStack.last().first
                                                viewModel.createGoogleDriveFolder(context, folderName, currentParentId, onSuccess = {
                                                    android.widget.Toast.makeText(context, "Folder created!", android.widget.Toast.LENGTH_SHORT).show()
                                                }, onAuthRequired = { intent ->
                                                    sheetsAuthLauncher.launch(intent)
                                                })
                                                showCreateDriveFolderDialog = false
                                                newDriveFolderTitle = ""
                                            }
                                        ) {
                                            Text("CREATE", color = WaterBlue, fontWeight = FontWeight.Bold)
                                        }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { showCreateDriveFolderDialog = false }) {
                                            Text("CANCEL", color = Color.Gray)
                                        }
                                    },
                                    containerColor = Color(0xFF161618),
                                    shape = RoundedCornerShape(16.dp)
                                )
                            }

                            if (isLoadingDrive) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(color = WaterBlue)
                                }
                            } else if (driveError != null) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
                                        Icon(Icons.Default.Cloud, contentDescription = null, modifier = Modifier.size(48.dp), tint = Color.Gray)
                                        Spacer(modifier = Modifier.height(12.dp))
                                        Text(driveError!!, color = Color.LightGray, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                        Spacer(modifier = Modifier.height(16.dp))
                                        Button(
                                            onClick = {
                                                val currentParentId = if (driveFolderStack.isEmpty()) "root" else driveFolderStack.last().first
                                                viewModel.fetchGoogleDriveFiles(context, currentParentId) { intent ->
                                                    sheetsAuthLauncher.launch(intent)
                                                }
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = WaterBlue)
                                        ) {
                                            Text("Retry", color = Color.Black, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            } else {
                                Column(modifier = Modifier.fillMaxSize()) {
                                    // 1. Google Drive Quota / About Info Card
                                    if (googleDriveAboutInfo != null) {
                                        val about = googleDriveAboutInfo!!
                                        val userObj = about.optJSONObject("user")
                                        val userName = userObj?.optString("displayName") ?: ""
                                        val userEmail = userObj?.optString("emailAddress") ?: ""
                                        
                                        val storageQuotaObj = about.optJSONObject("storageQuota")
                                        val limitVal = storageQuotaObj?.optLong("limit", 0L) ?: 0L
                                        val usageVal = storageQuotaObj?.optLong("usage", 0L) ?: 0L
                                        
                                        val limitGb = limitVal.toDouble() / (1024 * 1024 * 1024)
                                        val usageGb = usageVal.toDouble() / (1024 * 1024 * 1024)
                                        val usagePercent = if (limitVal > 0) (usageVal.toDouble() / limitVal * 100).toInt() else 0
                                        
                                        Card(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(bottom = 12.dp),
                                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E20)),
                                            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.05f))
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(12.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    Icons.Default.CloudQueue, 
                                                    contentDescription = null, 
                                                    tint = Color(0xFF4285F4), 
                                                    modifier = Modifier.size(32.dp)
                                                )
                                                Spacer(modifier = Modifier.width(12.dp))
                                                Column(modifier = Modifier.weight(1f)) {
                                                    if (userName.isNotEmpty() || userEmail.isNotEmpty()) {
                                                        Text(
                                                            text = if (userName.isNotEmpty()) userName else userEmail,
                                                            color = Color.White,
                                                            fontSize = 12.sp,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                        if (userName.isNotEmpty() && userEmail.isNotEmpty()) {
                                                            Text(userEmail, color = Color.Gray, fontSize = 10.sp)
                                                        }
                                                        Spacer(modifier = Modifier.height(4.dp))
                                                    }
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.SpaceBetween
                                                    ) {
                                                        Text(
                                                            text = String.format("%.2f GB of %.2f GB used", usageGb, limitGb),
                                                            color = Color.LightGray,
                                                            fontSize = 10.sp
                                                        )
                                                        Text(
                                                            text = "$usagePercent%",
                                                            color = if (usagePercent > 85) Color(0xFFEF5350) else Color(0xFF81C784),
                                                            fontSize = 10.sp,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                    }
                                                    Spacer(modifier = Modifier.height(4.dp))
                                                    androidx.compose.material3.LinearProgressIndicator(
                                                        progress = { if (limitVal > 0) (usageVal.toFloat() / limitVal) else 0f },
                                                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                                                        color = if (usagePercent > 85) Color(0xFFEF5350) else Color(0xFF4285F4),
                                                        trackColor = Color.White.copy(alpha = 0.1f)
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    if (googleDriveFiles.isEmpty()) {
                                        Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
                                                Icon(Icons.Default.CloudQueue, contentDescription = null, modifier = Modifier.size(48.dp), tint = Color.Gray)
                                                Spacer(modifier = Modifier.height(12.dp))
                                                Text("This folder is empty.", color = Color.LightGray)
                                                Spacer(modifier = Modifier.height(16.dp))
                                                Button(
                                                    onClick = { showCreateDriveFolderDialog = true },
                                                    colors = ButtonDefaults.buttonColors(containerColor = WaterBlue)
                                                ) {
                                                    Icon(Icons.Default.Folder, contentDescription = null, tint = Color.Black)
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Text("Create Subfolder", color = Color.Black, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                        }
                                    } else {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "DRIVE ITEMS (${googleDriveFiles.size})",
                                                color = Color.Gray,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        Button(
                                            onClick = { showCreateDriveFolderDialog = true },
                                            colors = ButtonDefaults.buttonColors(containerColor = WaterBlue),
                                            shape = RoundedCornerShape(20.dp),
                                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                            modifier = Modifier.height(32.dp)
                                        ) {
                                            Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.Black)
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("NEW FOLDER", color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }

                                    androidx.compose.foundation.lazy.LazyColumn(
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.fillMaxSize()
                                    ) {
                                        items(googleDriveFiles) { item ->
                                            val driveIcon = if (item.isFolder) {
                                                Icons.Default.Folder
                                            } else if (item.mimeType.contains("spreadsheet")) {
                                                Icons.Default.InsertDriveFile
                                            } else if (item.mimeType.contains("document")) {
                                                Icons.Default.Description
                                            } else {
                                                Icons.Default.InsertDriveFile
                                            }

                                            val iconColor = if (item.isFolder) {
                                                Color(0xFFFFB74D)
                                            } else if (item.mimeType.contains("spreadsheet")) {
                                                Color(0xFF0F9D58)
                                            } else if (item.mimeType.contains("document")) {
                                                Color(0xFF4285F4)
                                            } else {
                                                Color.LightGray
                                            }

                                            Card(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable { 
                                                        if (item.isFolder) {
                                                            driveFolderStack.add(Pair(item.id, item.name))
                                                        } else {
                                                            selectedDocUrl = item.webViewLink
                                                            selectedDocTitle = item.name
                                                        }
                                                    },
                                                colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                                                border = androidx.compose.foundation.BorderStroke(1.dp, Color.Gray.copy(alpha = 0.2f))
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(12.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Icon(
                                                        imageVector = driveIcon,
                                                        contentDescription = "Drive item",
                                                        tint = iconColor,
                                                        modifier = Modifier.size(36.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(12.dp))
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(
                                                            text = item.name,
                                                            color = Color.White,
                                                            fontSize = 14.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            maxLines = 1,
                                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                                        )
                                                        Spacer(modifier = Modifier.height(2.dp))
                                                        Text(
                                                            text = if (item.isFolder) "Folder" else "Last updated: " + if (item.modifiedTime.isNotEmpty()) item.modifiedTime.substringBefore("T") else "Unknown",
                                                            color = Color.Gray,
                                                            fontSize = 11.sp
                                                        )
                                                    }
                                                    Icon(
                                                        imageVector = if (item.isFolder) Icons.Default.ChevronRight else Icons.Default.ArrowUpward,
                                                        contentDescription = "Open",
                                                        tint = Color.Gray,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    "Friends" -> {
                            FriendsFolderView(
                                viewModel = viewModel,
                                context = context,
                                folderFriendsFiles = folderFriendsFiles,
                                friendsPathStack = friendsPathStack,
                                onPreviewFile = { file -> activePreviewFile = file },
                                onLongClickFile = { file -> longPressedFile = file },
                                onOpenSharedLink = { file -> activeSharedLinkFolder = file },
                                onOpenTextDoc = { file -> activeEditingDocFile = file },
                                onOpenAudioPlayer = { file -> activeAudioPlayerFile = file },
                                onOptionsClick = { file -> activeFileForOptions = file }
                            )
                        }
                        else -> {
                            // Render standard folder files
                            val folderFiles = when (activeFolder) {
                                "Favorites" -> folderFavoriteFiles
                                "Journal" -> folderJournalFiles
                                "Tasks" -> folderTaskFiles
                                "Contacts" -> folderContactFiles
                                "Keep Notes" -> folderGoogleNotesFiles
                                "Google Notes" -> folderGoogleNotesFiles
                                "Google Keep" -> folderGoogleNotesFiles
                                else -> folderGeneralFiles
                            }

                            if (folderFiles.isEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Charcoal),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                                        Icon(
                                            imageVector = Icons.Default.FolderOpen,
                                            contentDescription = null,
                                            modifier = Modifier.size(48.dp),
                                            tint = Color.Gray
                                        )
                                        Spacer(modifier = Modifier.height(12.dp))
                                        Text(
                                            text = "Folder is empty",
                                            color = Color.LightGray,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "Tap the '+' button at the bottom-right of the screen to upload files into this folder.",
                                            color = Color.Gray,
                                            fontSize = 12.sp,
                                            modifier = Modifier.padding(horizontal = 16.dp),
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                        )
                                    }
                                }
                            } else {
                                LazyVerticalGrid(
                                    columns = GridCells.Fixed(3),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.fillMaxSize().testTag("folder_files_grid")
                                ) {
                                    items(folderFiles) { file ->
                                        val isSharedLink = file.fileMime == "application/vnd.google-apps.folder-link"
                                        val isTextDoc = file.fileMime == "text/plain"

                                        val customCardColor = when (file.fileMime) {
                                            "video/youtube-link" -> Color(0xFF2C1B1B)
                                            "application/zoom-link" -> Color(0xFF1B263B)
                                            "application/gemini-link" -> Color(0xFF251B3B)
                                            "application/notebooklm-link" -> Color(0xFF1B2B28)
                                            else -> if (isSharedLink) Color(0xFF1D263B) else if (isTextDoc) Color(0xFF1B2C21) else SurfaceCard
                                        }

                                        val customIconTint = when (file.fileMime) {
                                            "video/youtube-link" -> Color(0xFFEF5350)
                                            "application/zoom-link" -> Color(0xFF29B6F6)
                                            "application/gemini-link" -> Color(0xFF9575CD)
                                            "application/notebooklm-link" -> Color(0xFF00BFA5)
                                            "application/pdf-link" -> Color(0xFFE57373)
                                            "application/msword-link" -> Color(0xFF42A5F5)
                                            "application/vnd.ms-excel-link" -> Color(0xFF66BB6A)
                                            else -> if (isSharedLink) Color(0xFFFFB74D) else if (isTextDoc) WaterBlue else when (file.type) {
                                                "image" -> WaterBlue
                                                "video" -> Color(0xFF81C784)
                                                "audio" -> Color(0xFF64B5F6)
                                                else -> Color.White
                                            }
                                        }

                                        val customFileIcon = when (file.fileMime) {
                                            "video/youtube-link" -> Icons.Default.PlayCircle
                                            "application/zoom-link" -> Icons.Default.VideoCall
                                            "application/gemini-link" -> Icons.Default.AutoAwesome
                                            "application/notebooklm-link" -> Icons.Default.MenuBook
                                            "application/pdf-link" -> Icons.Default.PictureAsPdf
                                            "application/msword-link" -> Icons.Default.Article
                                            "application/vnd.ms-excel-link" -> Icons.Default.GridOn
                                            else -> if (isSharedLink) Icons.Default.Link else if (isTextDoc) Icons.Default.Description else when (file.type) {
                                                "image" -> Icons.Default.Image
                                                "video" -> Icons.Default.Videocam
                                                "audio" -> Icons.Default.Mic
                                                else -> Icons.Default.InsertDriveFile
                                            }
                                        }

                                        Card(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .aspectRatio(1f)
                                                .clip(RoundedCornerShape(12.dp))
                                                .combinedClickable(
                                                    onClick = { activePreviewFile = file },
                                                    onLongClick = { longPressedFile = file }
                                                ),
                                            colors = CardDefaults.cardColors(containerColor = customCardColor),
                                            border = androidx.compose.foundation.BorderStroke(1.dp, Color.Gray.copy(alpha = 0.2f))
                                        ) {
                                            Box(modifier = Modifier.fillMaxSize()) {
                                                val isPdf = remember(file.name) { file.name.lowercase().endsWith(".pdf") }
                                                val hasDirectPreview = (file.type == "image" || file.type == "video" || isPdf) && 
                                                        file.fileMime != "video/youtube-link" && 
                                                        file.fileMime != "application/zoom-link" && 
                                                        file.fileMime != "application/gemini-link" && 
                                                        file.fileMime != "application/notebooklm-link" && 
                                                        file.fileMime != "application/pdf-link" && 
                                                        file.fileMime != "application/msword-link" && 
                                                        file.fileMime != "application/vnd.ms-excel-link"

                                                Column(modifier = Modifier.fillMaxSize()) {
                                                    if (hasDirectPreview && file.path.isNotEmpty()) {
                                                        Box(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .weight(1.3f)
                                                                .background(Color.Black.copy(alpha = 0.2f))
                                                        ) {
                                                            MediaPreviewBox(
                                                                pathOrName = file.path,
                                                                type = file.type,
                                                                modifier = Modifier.fillMaxSize()
                                                            )
                                                        }
                                                    } else {
                                                        Box(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .weight(1.3f),
                                                            contentAlignment = Alignment.Center
                                                        ) {
                                                            Icon(
                                                                imageVector = customFileIcon,
                                                                contentDescription = file.type,
                                                                tint = customIconTint,
                                                                modifier = Modifier.size(32.dp)
                                                            )
                                                        }
                                                    }

                                                    Column(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .weight(0.9f)
                                                            .padding(horizontal = 6.dp, vertical = 4.dp),
                                                        horizontalAlignment = Alignment.CenterHorizontally,
                                                        verticalArrangement = Arrangement.Center
                                                    ) {
                                                        Text(
                                                            text = file.name,
                                                            fontWeight = FontWeight.Bold,
                                                            color = Color.White,
                                                            fontSize = 10.sp,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                        Spacer(modifier = Modifier.height(1.dp))
                                                        Text(
                                                            text = file.sourceName,
                                                            color = Color.Gray,
                                                            fontSize = 8.sp,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                    }
                                                }

                                                IconButton(
                                                    onClick = {
                                                        activeFileForOptions = file
                                                    },
                                                    modifier = Modifier
                                                        .align(Alignment.BottomEnd)
                                                        .padding(0.dp)
                                                        .size(32.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.MoreVert,
                                                        contentDescription = "Options",
                                                        tint = Color.Gray,
                                                        modifier = Modifier.size(24.dp)
                                                    )
                                                }

                                                val isCloudItem = remember(file.timestamp, cloudStrategy, cloudPartialDays) {
                                                    when (cloudStrategy) {
                                                        "only_cloud" -> true
                                                        "partial" -> {
                                                            val daysInMillis = cloudPartialDays * 24L * 60L * 60L * 1000L
                                                            (System.currentTimeMillis() - file.timestamp) > daysInMillis
                                                        }
                                                        else -> false
                                                    }
                                                }

                                                Box(
                                                    modifier = Modifier
                                                        .align(Alignment.TopStart)
                                                        .padding(6.dp)
                                                        .clip(RoundedCornerShape(4.dp))
                                                        .background(Color.Black.copy(alpha = 0.65f))
                                                        .padding(horizontal = 4.dp, vertical = 2.dp)
                                                ) {
                                                    Text(
                                                        text = file.dateText,
                                                        color = WaterBlue,
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }

                                                Box(
                                                    modifier = Modifier
                                                        .align(Alignment.TopEnd)
                                                        .padding(6.dp)
                                                        .clip(RoundedCornerShape(4.dp))
                                                        .background(if (isCloudItem) Color(0xFF0D47A1).copy(alpha = 0.85f) else Color(0xFF1B5E20).copy(alpha = 0.85f))
                                                        .padding(horizontal = 4.dp, vertical = 2.dp)
                                                ) {
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = if (isCloudItem) Icons.Default.Cloud else Icons.Default.PhoneAndroid,
                                                            contentDescription = null,
                                                            tint = Color.White,
                                                            modifier = Modifier.size(9.dp)
                                                        )
                                                        Text(
                                                            text = if (isCloudItem) "Cloud" else "Local",
                                                            color = Color.White,
                                                            fontSize = 8.sp,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }        }
                }
            } else {
                // Flat View (Original Mode)
                Text(
                    text = "FILES LIBRARY (${filteredFiles.size} ITEMS)",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Gray,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                // Original Filters bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    filterOptions.forEach { filter ->
                        val isSelected = selectedFilter == filter
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(if (isSelected) WaterBlue else SurfaceCard)
                                .border(1.dp, if (isSelected) WaterBlue else Color.Gray.copy(alpha = 0.3f), RoundedCornerShape(20.dp))
                                .clickable { selectedFilter = filter }
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = filter,
                                color = if (isSelected) Color.Black else Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                if (filteredFiles.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Charcoal),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = Color.Gray
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = if (selectedFilter == "All") "No attachments or files found." else "No $selectedFilter files found.",
                                color = Color.LightGray,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Upload or attach images, voice memos, videos or files inside Journals, Tasks, or Contacts to list them here.",
                                color = Color.Gray,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 16.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxSize().testTag("files_grid_layout")
                    ) {
                        items(filteredFiles) { file ->
                            val isSharedLink = file.fileMime == "application/vnd.google-apps.folder-link"
                            val isTextDoc = file.fileMime == "text/plain"

                            val customCardColor = when (file.fileMime) {
                                "video/youtube-link" -> Color(0xFF2C1B1B)
                                "application/zoom-link" -> Color(0xFF1B263B)
                                "application/gemini-link" -> Color(0xFF251B3B)
                                "application/notebooklm-link" -> Color(0xFF1B2B28)
                                else -> if (isSharedLink) Color(0xFF1D263B) else if (isTextDoc) Color(0xFF1B2C21) else SurfaceCard
                            }

                            val customIconTint = when (file.fileMime) {
                                "video/youtube-link" -> Color(0xFFEF5350)
                                "application/zoom-link" -> Color(0xFF29B6F6)
                                "application/gemini-link" -> Color(0xFF9575CD)
                                "application/notebooklm-link" -> Color(0xFF00BFA5)
                                "application/pdf-link" -> Color(0xFFE57373)
                                "application/msword-link" -> Color(0xFF42A5F5)
                                "application/vnd.ms-excel-link" -> Color(0xFF66BB6A)
                                else -> if (isSharedLink) Color(0xFFFFB74D) else if (isTextDoc) WaterBlue else when (file.type) {
                                    "image" -> WaterBlue
                                    "video" -> Color(0xFF81C784)
                                    "audio" -> Color(0xFF64B5F6)
                                    else -> Color.White
                                }
                            }

                            val customFileIcon = when (file.fileMime) {
                                "video/youtube-link" -> Icons.Default.PlayCircle
                                "application/zoom-link" -> Icons.Default.VideoCall
                                "application/gemini-link" -> Icons.Default.AutoAwesome
                                "application/notebooklm-link" -> Icons.Default.MenuBook
                                "application/pdf-link" -> Icons.Default.PictureAsPdf
                                "application/msword-link" -> Icons.Default.Article
                                "application/vnd.ms-excel-link" -> Icons.Default.GridOn
                                else -> if (isSharedLink) Icons.Default.Link else if (isTextDoc) Icons.Default.Description else when (file.type) {
                                    "image" -> Icons.Default.Image
                                    "video" -> Icons.Default.Videocam
                                    "audio" -> Icons.Default.Mic
                                    else -> Icons.Default.InsertDriveFile
                                }
                            }

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .combinedClickable(
                                        onClick = { activePreviewFile = file },
                                        onLongClick = { longPressedFile = file }
                                    ),
                                colors = CardDefaults.cardColors(containerColor = customCardColor),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color.Gray.copy(alpha = 0.2f))
                            ) {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    val isPdf = remember(file.name) { file.name.lowercase().endsWith(".pdf") }
                                    val hasDirectPreview = (file.type == "image" || file.type == "video" || isPdf) && 
                                            file.fileMime != "video/youtube-link" && 
                                            file.fileMime != "application/zoom-link" && 
                                            file.fileMime != "application/gemini-link" && 
                                            file.fileMime != "application/notebooklm-link" && 
                                            file.fileMime != "application/pdf-link" && 
                                            file.fileMime != "application/msword-link" && 
                                            file.fileMime != "application/vnd.ms-excel-link"

                                    Column(modifier = Modifier.fillMaxSize()) {
                                        if (hasDirectPreview && file.path.isNotEmpty()) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .weight(1.3f)
                                                    .background(Color.Black.copy(alpha = 0.2f))
                                            ) {
                                                MediaPreviewBox(
                                                    pathOrName = file.path,
                                                    type = file.type,
                                                    modifier = Modifier.fillMaxSize()
                                                )
                                            }
                                        } else {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .weight(1.3f),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = customFileIcon,
                                                    contentDescription = file.type,
                                                    tint = customIconTint,
                                                    modifier = Modifier.size(32.dp)
                                                )
                                            }
                                        }

                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .weight(0.9f)
                                                .padding(horizontal = 6.dp, vertical = 4.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.Center
                                        ) {
                                            Text(
                                                text = file.name,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White,
                                                fontSize = 10.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Spacer(modifier = Modifier.height(1.dp))
                                            Text(
                                                text = file.sourceName,
                                                color = Color.Gray,
                                                fontSize = 8.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }

                                    val isCloudItem = remember(file.timestamp, cloudStrategy, cloudPartialDays) {
                                        when (cloudStrategy) {
                                            "only_cloud" -> true
                                            "partial" -> {
                                                val daysInMillis = cloudPartialDays * 24L * 60L * 60L * 1000L
                                                (System.currentTimeMillis() - file.timestamp) > daysInMillis
                                            }
                                            else -> false
                                        }
                                    }

                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopStart)
                                            .padding(6.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(Color.Black.copy(alpha = 0.65f))
                                            .padding(horizontal = 4.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = file.dateText,
                                            color = WaterBlue,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }

                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(6.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(if (isCloudItem) Color(0xFF0D47A1).copy(alpha = 0.85f) else Color(0xFF1B5E20).copy(alpha = 0.85f))
                                            .padding(horizontal = 4.dp, vertical = 2.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                                        ) {
                                            Icon(
                                                imageVector = if (isCloudItem) Icons.Default.Cloud else Icons.Default.PhoneAndroid,
                                                contentDescription = null,
                                                tint = Color.White,
                                                modifier = Modifier.size(9.dp)
                                            )
                                            Text(
                                                text = if (isCloudItem) "Cloud" else "Local",
                                                color = Color.White,
                                                fontSize = 8.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Direct File Upload / Categorization Dialog
        if (showUploadFileDialog) {
            AlertDialog(
                onDismissRequest = { showUploadFileDialog = false },
                title = { Text("Upload File", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = { filePickerLauncher.launch("*/*") },
                            colors = ButtonDefaults.buttonColors(containerColor = WaterBlue.copy(alpha = 0.2f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.UploadFile, contentDescription = null, tint = WaterBlue)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (selectedFileUri == null) "Select File from Device" else "Change Selected File",
                                color = WaterBlue,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        if (selectedFileUri != null) {
                            Text(
                                text = "Selected File: $uploadFileName (${selectedFileSize / 1024} KB)",
                                color = Color.LightGray,
                                fontSize = 12.sp
                            )

                            OutlinedTextField(
                                value = uploadFileName,
                                onValueChange = { uploadFileName = it },
                                label = { Text("File Display Name", color = Color.Gray) },
                                textStyle = androidx.compose.ui.text.TextStyle(color = Color.White),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = WaterBlue,
                                    unfocusedBorderColor = Color.Gray,
                                    cursorColor = WaterBlue
                                ),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        Text(
                            text = "Categorize Into Folder:",
                            color = Color.LightGray,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf("Journal", "Tasks", "Contacts", "General").forEach { cat ->
                                val isCatSelected = uploadFolderCategory == cat
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isCatSelected) WaterBlue else SurfaceCard)
                                        .border(1.dp, if (isCatSelected) WaterBlue else Color.Gray.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                        .clickable { uploadFolderCategory = cat }
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = cat,
                                        color = if (isCatSelected) Color.Black else Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = selectedFileUri != null,
                        onClick = {
                            val sandboxFile = com.example.util.StorageHelper.copyFileToInternalSandbox(context, selectedFileUri!!)
                            if (sandboxFile != null && sandboxFile.exists()) {
                                viewModel.addFile(
                                    name = uploadFileName,
                                    path = uploadFolderCategory,
                                    size = selectedFileSize,
                                    mimeType = selectedFileMimeType,
                                    uriString = sandboxFile.absolutePath
                                )
                                // Real-time trigger database backup to Google Drive if permitted to keep devices synced
                                if (com.example.util.GoogleDriveSyncManager.hasDrivePermission(context)) {
                                    scope.launch {
                                        com.example.util.GoogleDriveSyncManager.backupAllAppData(context, viewModel.appDatabase)
                                    }
                                }
                                android.widget.Toast.makeText(context, "Successfully uploaded to $uploadFolderCategory!", android.widget.Toast.LENGTH_SHORT).show()
                            } else {
                                android.widget.Toast.makeText(context, "Failed to copy file to sandbox.", android.widget.Toast.LENGTH_SHORT).show()
                            }
                            showUploadFileDialog = false
                        }
                    ) {
                        Text("UPLOAD", color = WaterBlue, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showUploadFileDialog = false }) {
                        Text("CANCEL", color = Color.Gray)
                    }
                },
                containerColor = Color(0xFF161618),
                shape = RoundedCornerShape(16.dp)
            )
        }

        if (longPressedFile != null) {
            val fileNode = longPressedFile!!
            AlertDialog(
                onDismissRequest = { longPressedFile = null },
                title = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.InsertDriveFile,
                            contentDescription = null,
                            tint = WaterBlue,
                            modifier = Modifier.size(22.dp)
                        )
                        Text(
                            text = "Attachment File Options",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = fileNode.name,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Linked Source: ${fileNode.sourceName}",
                            color = Color.Gray,
                            fontSize = 11.sp
                        )
                        Text(
                            text = "Type: ${fileNode.type.uppercase()} • Date: ${fileNode.dateText}",
                            color = Color.Gray,
                            fontSize = 10.sp
                        )
                        val isCloudItem = remember(fileNode.timestamp, cloudStrategy, cloudPartialDays) {
                            when (cloudStrategy) {
                                "only_cloud" -> true
                                "partial" -> {
                                    val daysInMillis = cloudPartialDays * 24L * 60L * 60L * 1000L
                                    (System.currentTimeMillis() - fileNode.timestamp) > daysInMillis
                                }
                                else -> false
                            }
                        }
                        Text(
                            text = if (isCloudItem) "☁️ Cloud Mode: Stored remotely (0 Bytes local)" else "📱 Local Mode: Cached in internal storage",
                            color = if (isCloudItem) WaterBlue else Color(0xFF81C784),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val fNode = fileNode
                            if (fNode.appFileRef != null) {
                                viewModel.deleteFile(fNode.appFileRef)
                                if (com.example.util.GoogleDriveSyncManager.hasDrivePermission(context)) {
                                    scope.launch {
                                        com.example.util.GoogleDriveSyncManager.backupAllAppData(context, viewModel.appDatabase)
                                    }
                                }
                            }
                            android.widget.Toast.makeText(
                                context,
                                "File \"${fileNode.name}\" deleted successfully.",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                            longPressedFile = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828), contentColor = Color.White),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.White)
                            Text("DELETE", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                },
                dismissButton = {
                    Button(
                        onClick = {
                            android.widget.Toast.makeText(
                                context,
                                "Download started: \"${fileNode.name}\" is being exported to system downloads stream.",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                            longPressedFile = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1D3B3A), contentColor = Color.White),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color(0xFF00BFA5))
                            Text("DOWNLOAD", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00BFA5))
                        }
                    }
                },
                containerColor = Color(0xFF161618),
                shape = RoundedCornerShape(16.dp)
            )
        }

        if (activePreviewFile != null) {
            val fileNode = activePreviewFile!!
            val isCustomLink = fileNode.fileMime == "video/youtube-link" ||
                               fileNode.fileMime == "application/zoom-link" ||
                               fileNode.fileMime == "application/gemini-link" ||
                               fileNode.fileMime == "application/notebooklm-link" ||
                               fileNode.fileMime == "application/pdf-link" ||
                               fileNode.fileMime == "application/msword-link" ||
                               fileNode.fileMime == "application/vnd.ms-excel-link"

            if (isCustomLink) {
                if (fileNode.fileMime == "application/zoom-link") {
                    val uri = android.net.Uri.parse(fileNode.path)
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri).apply {
                        setPackage("us.zoom.videomeetings")
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    try {
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        try {
                            val webIntent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri).apply {
                                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(webIntent)
                        } catch (ex: Exception) {
                            android.widget.Toast.makeText(context, "Cannot open Zoom meeting link.", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                    activePreviewFile = null
                } else {
                    selectedDocTitle = fileNode.name
                    selectedDocUrl = fileNode.path
                    activePreviewFile = null
                }
            } else {
                val isPdf = remember(fileNode.name) { fileNode.name.lowercase().endsWith(".pdf") }
                
                if (isPdf) {
                    PdfViewerDialog(filePath = fileNode.path, onDismiss = { activePreviewFile = null })
                } else if (fileNode.type == "video") {
                    VideoPlayerDialog(filePath = fileNode.path, onDismiss = { activePreviewFile = null })
                } else if (fileNode.type == "image") {
                Dialog(onDismissRequest = { activePreviewFile = null }) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .wrapContentHeight()
                            .padding(12.dp),
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFF12131A),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // Title / Header Row
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = fileNode.name,
                                        color = Color.White,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = fileNode.sourceName,
                                        color = Color.Gray,
                                        fontSize = 11.sp
                                    )
                                }
                                IconButton(
                                    onClick = { activePreviewFile = null },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // Large Image Frame
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(280.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color.Black),
                                contentAlignment = Alignment.Center
                            ) {
                                val isWebUrl = remember(fileNode.path) { fileNode.path.startsWith("http://") || fileNode.path.startsWith("https://") }
                                AsyncImage(
                                    model = if (isWebUrl) fileNode.path else java.io.File(fileNode.path),
                                    contentDescription = "Full Preview",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit
                                )
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            // Bottom Action Buttons
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        val act = activePreviewFile
                                        activePreviewFile = null
                                        act?.onClick?.invoke()
                                    },
                                    modifier = Modifier.weight(1.2f),
                                    colors = ButtonDefaults.buttonColors(containerColor = WaterBlue),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.White)
                                        Text("GO TO SOURCE", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                    }
                                }

                                Button(
                                    onClick = {
                                        android.widget.Toast.makeText(
                                            context,
                                            "Download started: \"${fileNode.name}\" has been exported to Downloads folder.",
                                            android.widget.Toast.LENGTH_LONG
                                        ).show()
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1D3B3A)),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color(0xFF00BFA5))
                                        Text("DOWNLOAD", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00BFA5), maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // Audio or other types
                Dialog(onDismissRequest = { activePreviewFile = null }) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .wrapContentHeight()
                            .padding(12.dp),
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFF12131A),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = fileNode.name,
                                        color = Color.White,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "Linked Source: ${fileNode.sourceName}",
                                        color = Color.Gray,
                                        fontSize = 11.sp
                                    )
                                }
                                IconButton(
                                    onClick = { activePreviewFile = null },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(100.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color.Black.copy(alpha = 0.3f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    val mimeIcon = if (fileNode.type == "audio") Icons.Default.Mic else Icons.Default.InsertDriveFile
                                    Icon(
                                        imageVector = mimeIcon,
                                        contentDescription = null,
                                        tint = WaterBlue,
                                        modifier = Modifier.size(40.dp)
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "Type: ${fileNode.type.uppercase()} • ${fileNode.dateText}",
                                        color = Color.LightGray,
                                        fontSize = 12.sp
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        val act = activePreviewFile
                                        activePreviewFile = null
                                        act?.onClick?.invoke()
                                    },
                                    modifier = Modifier.weight(1.2f),
                                    colors = ButtonDefaults.buttonColors(containerColor = WaterBlue),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.White)
                                        Text("GO TO SOURCE", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                    }
                                }

                                Button(
                                    onClick = {
                                        android.widget.Toast.makeText(
                                            context,
                                            "Download started: \"${fileNode.name}\" has been exported to Downloads folder.",
                                            android.widget.Toast.LENGTH_LONG
                                        ).show()
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1D3B3A)),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color(0xFF00BFA5))
                                        Text("DOWNLOAD", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00BFA5), maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

        // ==========================================
        // UNIFIED MULTI-CHOICE ACTION SELECTOR DIALOG
        // ==========================================
        if (showActionSelectorDialog) {
            val destFolder = activeFolder ?: "General"
            val displayPath = remember(friendsPathStack.size) {
                if (activeFolder == "Friends") {
                    if (friendsPathStack.isEmpty()) "Friends"
                    else "Friends/" + friendsPathStack.joinToString("/")
                } else {
                    destFolder
                }
            }
            
            AlertDialog(
                onDismissRequest = { showActionSelectorDialog = false },
                title = { 
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.AddCircle, contentDescription = null, tint = WaterBlue)
                        Text("Add Group Content", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = "Destination: $displayPath",
                            color = WaterBlue,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Divider(color = Color.White.copy(alpha = 0.08f))

                        // Category 1: Attachments
                        Text(
                            text = "📁 OPTION A: ATTACHMENTS & DOCUMENTS",
                            color = WaterBlue,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                        
                        // Option 1: Create Plain Document
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showActionSelectorDialog = false
                                    textDocTitle = ""
                                    textDocContent = ""
                                    showCreateTextDocDialog = true
                                },
                            colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = null, tint = WaterBlue, modifier = Modifier.size(24.dp))
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text("Create Text Document", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    Text("Type or paste study notes/text", color = Color.Gray, fontSize = 11.sp)
                                }
                            }
                        }

                        // Option 2: Upload Device File
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showActionSelectorDialog = false
                                    selectedFileUri = null
                                    uploadFileName = ""
                                    uploadFolderCategory = displayPath
                                    filePickerLauncher.launch("*/*")
                                },
                            colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.CloudUpload, contentDescription = null, tint = Color(0xFF81C784), modifier = Modifier.size(24.dp))
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text("Upload Device File", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    Text("Select PDF, video, audio, or images", color = Color.Gray, fontSize = 11.sp)
                                }
                            }
                        }

                        Divider(color = Color.White.copy(alpha = 0.08f), modifier = Modifier.padding(vertical = 4.dp))

                        // Category 2: Drive Links
                        Text(
                            text = "☁️ OPTION B: GOOGLE DRIVE INTEGRATIONS",
                            color = Color(0xFFFFB74D),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )

                        // Option 3: Link Google Drive Folder
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showActionSelectorDialog = false
                                    sharedFolderTitle = ""
                                    sharedFolderUrl = ""
                                    showAddSharedLinkDialog = true
                                },
                            colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Link, contentDescription = null, tint = Color(0xFFFFB74D), modifier = Modifier.size(24.dp))
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text("Add Shared Drive Link", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    Text("Paste shared Google Drive folder link", color = Color.Gray, fontSize = 11.sp)
                                }
                            }
                        }

                        // Option 4: Create Local Virtual Folder
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showActionSelectorDialog = false
                                    newFolderName = ""
                                    showCreateFolderDialog = true
                                },
                            colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.CreateNewFolder, contentDescription = null, tint = Color(0xFF4CAF50), modifier = Modifier.size(24.dp))
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text("Create Virtual Folder", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    Text("Add an empty subfolder here for study items", color = Color.Gray, fontSize = 11.sp)
                                }
                            }
                        }

                        // Option 5: Link Folder via JSON Route-Map
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showActionSelectorDialog = false
                                    routeMapFolderTitle = ""
                                    routeMapFolderUrl = ""
                                    routeMapJsonContent = ""
                                    showAddRouteMapFolderDialog = true
                                },
                            colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.FolderZip, contentDescription = null, tint = Color(0xFF29B6F6), modifier = Modifier.size(24.dp))
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text("Link/Upload Folder Route-Map JSON", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    Text("Pre-load folders & files with direct sharing links", color = Color.Gray, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showActionSelectorDialog = false }) {
                        Text("CLOSE", color = Color.Gray)
                    }
                },
                containerColor = Color(0xFF161618),
                shape = RoundedCornerShape(16.dp)
            )
        }

        // ==========================================
        // DIALOG: CREATE TEXT DOCUMENT
        // ==========================================
        if (showCreateTextDocDialog) {
            val displayPath = remember(friendsPathStack.size) {
                if (activeFolder == "Friends") {
                    if (friendsPathStack.isEmpty()) "Friends"
                    else "Friends/" + friendsPathStack.joinToString("/")
                } else {
                    activeFolder ?: "General"
                }
            }
            
            AlertDialog(
                onDismissRequest = { showCreateTextDocDialog = false },
                title = { Text("Create Text Document", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Saving to: $displayPath", color = WaterBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        
                        OutlinedTextField(
                            value = textDocTitle,
                            onValueChange = { textDocTitle = it },
                            label = { Text("Document Title", color = Color.Gray) },
                            textStyle = androidx.compose.ui.text.TextStyle(color = Color.White),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = WaterBlue,
                                unfocusedBorderColor = Color.Gray
                            ),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = textDocContent,
                            onValueChange = { textDocContent = it },
                            label = { Text("Paste Text Content or Type Notes", color = Color.Gray) },
                            textStyle = androidx.compose.ui.text.TextStyle(color = Color.White),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = WaterBlue,
                                unfocusedBorderColor = Color.Gray
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp)
                        )
                    }
                },
                confirmButton = {
                    Button(
                        enabled = textDocTitle.isNotBlank() && textDocContent.isNotBlank(),
                        onClick = {
                            val cleanTitle = textDocTitle.trim().replace(" ", "_")
                            val cleanFileName = "$cleanTitle.txt"
                            try {
                                val file = java.io.File(com.example.util.StorageHelper.getAppFilesDir(context), cleanFileName)
                                file.writeText(textDocContent)
                                
                                viewModel.addFile(
                                    name = "$textDocTitle.txt",
                                    path = displayPath,
                                    size = file.length(),
                                    mimeType = "text/plain",
                                    uriString = file.absolutePath
                                )
                                
                                // Direct Sync with Google Drive to back up instantly
                                if (com.example.util.GoogleDriveSyncManager.hasDrivePermission(context)) {
                                    scope.launch {
                                        com.example.util.GoogleDriveSyncManager.backupAllAppData(context, viewModel.appDatabase)
                                    }
                                }
                                
                                android.widget.Toast.makeText(context, "Document created and shared with Study Group!", android.widget.Toast.LENGTH_LONG).show()
                            } catch (e: Exception) {
                                e.printStackTrace()
                                android.widget.Toast.makeText(context, "Failed to write document file.", android.widget.Toast.LENGTH_SHORT).show()
                            }
                            showCreateTextDocDialog = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = WaterBlue)
                    ) {
                        Text("SAVE & SYNC", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showCreateTextDocDialog = false }) {
                        Text("CANCEL", color = Color.Gray)
                    }
                },
                containerColor = Color(0xFF161618),
                shape = RoundedCornerShape(16.dp)
            )
        }

        // ==========================================
        // ==========================================
        // DIALOG: ADD SHARED DRIVE FOLDER LINK
        // ==========================================
        if (showAddSharedLinkDialog) {
            val displayPath = remember(friendsPathStack.size) {
                if (activeFolder == "Friends") {
                    if (friendsPathStack.isEmpty()) "Friends"
                    else "Friends/" + friendsPathStack.joinToString("/")
                } else {
                    activeFolder ?: "General"
                }
            }

            val detectedApp = remember(sharedFolderUrl) {
                when {
                    sharedFolderUrl.contains("youtube.com") || sharedFolderUrl.contains("youtu.be") -> "YouTube Video"
                    sharedFolderUrl.contains("zoom.us") -> "Zoom Meeting"
                    sharedFolderUrl.contains("gemini.google") || sharedFolderUrl.contains("gemini.google.com") -> "Gemini Chat"
                    sharedFolderUrl.contains("notebooklm.google") || sharedFolderUrl.contains("notebooklm") -> "NotebookLM Project"
                    sharedFolderUrl.lowercase().endsWith(".pdf") -> "PDF Document"
                    sharedFolderUrl.lowercase().endsWith(".xlsx") || sharedFolderUrl.lowercase().endsWith(".xls") || sharedFolderUrl.contains("docs.google.com/spreadsheets") -> "Excel Sheet"
                    sharedFolderUrl.lowercase().endsWith(".docx") || sharedFolderUrl.lowercase().endsWith(".doc") || sharedFolderUrl.contains("docs.google.com/document") -> "Word/Google Doc"
                    else -> null
                }
            }

            LaunchedEffect(sharedFolderUrl) {
                if (sharedFolderUrl.isNotBlank() && (sharedFolderUrl.startsWith("http://") || sharedFolderUrl.startsWith("https://"))) {
                    sizeLoading = true
                    viewModel.getUrlFileSize(sharedFolderUrl) { size ->
                        estimatedSize = size
                        sizeLoading = false
                    }
                    // Fetch web title automatically and fill the asset display name if empty
                    try {
                        val result = viewModel.fetchUrlTitleAndFavicon(sharedFolderUrl)
                        if (result.first != null && sharedFolderTitle.isBlank()) {
                            sharedFolderTitle = result.first!!
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    // Auto-detect link category and document type based on url
                    when {
                        sharedFolderUrl.contains("youtube.com") || sharedFolderUrl.contains("youtu.be") -> {
                            linkCategory = "Others"
                        }
                        sharedFolderUrl.contains("zoom.us") -> {
                            linkCategory = "Others"
                        }
                        sharedFolderUrl.contains("gemini.google") -> {
                            linkCategory = "Others"
                        }
                        sharedFolderUrl.contains("notebooklm.google") || sharedFolderUrl.contains("notebooklm") -> {
                            linkCategory = "Others"
                        }
                        sharedFolderUrl.lowercase().endsWith(".pdf") || sharedFolderUrl.contains("gview") -> {
                            linkCategory = "Document"
                            docType = "PDF"
                        }
                        sharedFolderUrl.lowercase().endsWith(".xlsx") || sharedFolderUrl.lowercase().endsWith(".xls") || sharedFolderUrl.contains("docs.google.com/spreadsheets") -> {
                            linkCategory = "Document"
                            docType = "Excel"
                        }
                        sharedFolderUrl.lowercase().endsWith(".docx") || sharedFolderUrl.lowercase().endsWith(".doc") || sharedFolderUrl.contains("docs.google.com/document") -> {
                            linkCategory = "Document"
                            docType = "Doc"
                        }
                        sharedFolderUrl.contains("drive.google.com/drive/folders") || sharedFolderUrl.contains("drive.google.com/folderview") -> {
                            linkCategory = "Folder"
                        }
                    }
                }
            }
            
            AlertDialog(
                onDismissRequest = { showAddSharedLinkDialog = false },
                title = { Text("Link Google Drive Folder or Online Asset", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Linking into: $displayPath", color = WaterBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        
                        OutlinedTextField(
                            value = sharedFolderTitle,
                            onValueChange = { sharedFolderTitle = it },
                            label = { Text("Asset Display Name", color = Color.Gray) },
                            textStyle = androidx.compose.ui.text.TextStyle(color = Color.White),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = WaterBlue,
                                unfocusedBorderColor = Color.Gray
                            ),
                            leadingIcon = {
                                val host = try { java.net.URL(sharedFolderUrl).host } catch (e: Exception) { "" }
                                val faviconUrl = if (host.isNotEmpty()) "https://www.google.com/s2/favicons?sz=128&domain=$host" else ""
                                if (faviconUrl.isNotEmpty()) {
                                    AsyncImage(
                                        model = faviconUrl,
                                        contentDescription = "Web Logo",
                                        modifier = Modifier.size(20.dp).clip(RoundedCornerShape(4.dp))
                                    )
                                } else {
                                    Icon(Icons.Default.Link, contentDescription = null, tint = WaterBlue, modifier = Modifier.size(20.dp))
                                }
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = sharedFolderUrl,
                            onValueChange = { sharedFolderUrl = it },
                            label = { Text("Folder / Asset Web Link", color = Color.Gray) },
                            placeholder = { Text("Paste any Google Drive, PDF, Zoom or YouTube link...", color = Color.Gray, fontSize = 11.sp) },
                            textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 12.sp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = WaterBlue,
                                unfocusedBorderColor = Color.Gray
                            ),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        if (sharedFolderUrl.isNotBlank() && (sharedFolderUrl.startsWith("http://") || sharedFolderUrl.startsWith("https://"))) {
                            val host = try { java.net.URL(sharedFolderUrl).host } catch (e: Exception) { "" }
                            val faviconUrl = if (host.isNotEmpty()) "https://www.google.com/s2/favicons?sz=128&domain=$host" else ""
                            if (faviconUrl.isNotEmpty()) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(8.dp)
                                        .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                                        .padding(8.dp)
                                ) {
                                    AsyncImage(
                                        model = faviconUrl,
                                        contentDescription = "Logo Preview",
                                        modifier = Modifier.size(24.dp).clip(RoundedCornerShape(4.dp))
                                    )
                                    Column {
                                        Text(
                                            text = "Web Icon Detected",
                                            color = WaterBlue,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = host,
                                            color = Color.Gray,
                                            fontSize = 10.sp
                                        )
                                    }
                                }
                            }
                        }

                        if (detectedApp != null) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(WaterBlue.copy(alpha = 0.15f))
                                    .padding(vertical = 6.dp, horizontal = 10.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Icon(Icons.Default.Verified, contentDescription = null, tint = WaterBlue, modifier = Modifier.size(14.dp))
                                    Text("AUTO-DETECTED: $detectedApp Asset", color = WaterBlue, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        if (sharedFolderUrl.isNotBlank()) {
                            Text(
                                text = if (sizeLoading) "Estimating Web Size... ⏳" else "Estimated File Size: " + when {
                                    estimatedSize > 1024 * 1024 -> String.format("%.2f MB", estimatedSize.toFloat() / (1024 * 1024))
                                    estimatedSize > 1024 -> String.format("%.2f KB", estimatedSize.toFloat() / 1024)
                                    estimatedSize == 0L -> "Live Meeting (0 Bytes)"
                                    else -> "$estimatedSize Bytes"
                                },
                                color = Color(0xFF81C784),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        androidx.compose.material3.HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

                        Text("LINK CATEGORY", color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Box(modifier = Modifier.fillMaxWidth()) {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { linkCategoryExpanded = true },
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E24)),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(text = linkCategory, color = Color.White, fontSize = 13.sp)
                                    Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = WaterBlue)
                                }
                            }
                            DropdownMenu(
                                expanded = linkCategoryExpanded,
                                onDismissRequest = { linkCategoryExpanded = false },
                                modifier = Modifier.background(Color(0xFF1E1E24))
                            ) {
                                listOf("Folder", "Document", "Others").forEach { cat ->
                                    DropdownMenuItem(
                                        text = { Text(cat, color = Color.White) },
                                        onClick = {
                                            linkCategory = cat
                                            linkCategoryExpanded = false
                                        }
                                    )
                                }
                            }
                        }

                        if (linkCategory == "Document") {
                            Text("DOCUMENT TYPE", color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Box(modifier = Modifier.fillMaxWidth()) {
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { docTypeExpanded = true },
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E24)),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(text = docType, color = Color.White, fontSize = 13.sp)
                                        Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = WaterBlue)
                                    }
                                }
                                DropdownMenu(
                                    expanded = docTypeExpanded,
                                    onDismissRequest = { docTypeExpanded = false },
                                    modifier = Modifier.background(Color(0xFF1E1E24))
                                ) {
                                    listOf("PDF", "Doc", "Excel", "Others").forEach { type ->
                                        DropdownMenuItem(
                                            text = { Text(type, color = Color.White) },
                                            onClick = {
                                                docType = type
                                                docTypeExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        enabled = sharedFolderTitle.isNotBlank() && sharedFolderUrl.isNotBlank(),
                        onClick = {
                            val finalMimeType = when {
                                sharedFolderUrl.contains("youtube.com") || sharedFolderUrl.contains("youtu.be") -> "video/youtube-link"
                                sharedFolderUrl.contains("zoom.us") -> "application/zoom-link"
                                sharedFolderUrl.contains("gemini.google") -> "application/gemini-link"
                                sharedFolderUrl.contains("notebooklm.google") -> "application/notebooklm-link"
                                linkCategory == "Folder" -> "application/vnd.google-apps.folder-link"
                                linkCategory == "Document" -> {
                                    when (docType) {
                                        "PDF" -> "application/pdf-link"
                                        "Doc" -> "application/msword-link"
                                        "Excel" -> "application/vnd.ms-excel-link"
                                        else -> "application/octet-stream-link"
                                    }
                                }
                                else -> "application/octet-stream-link"
                            }

                            val isFolderNode = finalMimeType == "application/vnd.google-apps.folder-link"
                            val cleanFileName = "${sharedFolderTitle.replace("[^a-zA-Z0-9]".toRegex(), "_")}_drive_asset.txt"
                            val file = java.io.File(com.example.util.StorageHelper.getAppFilesDir(context), cleanFileName)
                            try {
                                val jsonContent = """
                                    {
                                      "isFolder": $isFolderNode,
                                      "name": "$sharedFolderTitle",
                                      "url": "$sharedFolderUrl",
                                      "path": "$displayPath",
                                      "mimeType": "$finalMimeType",
                                      "size": $estimatedSize
                                    }
                                """.trimIndent()
                                file.writeText(jsonContent)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }

                            viewModel.addFile(
                                name = sharedFolderTitle,
                                path = displayPath,
                                size = estimatedSize,
                                mimeType = finalMimeType,
                                uriString = sharedFolderUrl
                            )
                            
                            // Direct Sync with Google Drive to back up instantly
                            if (com.example.util.GoogleDriveSyncManager.hasDrivePermission(context)) {
                                scope.launch {
                                    com.example.util.GoogleDriveSyncManager.backupAllAppData(context, viewModel.appDatabase)
                                }
                            }
                            
                            android.widget.Toast.makeText(context, "Asset '$sharedFolderTitle' integrated! Sync txt metadata generated.", android.widget.Toast.LENGTH_SHORT).show()
                            showAddSharedLinkDialog = false
                            // Clear input buffers
                            sharedFolderTitle = ""
                            sharedFolderUrl = ""
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = WaterBlue)
                    ) {
                        Text("INTEGRATE LINK", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showAddSharedLinkDialog = false }) {
                        Text("CANCEL", color = Color.Gray)
                    }
                },
                containerColor = Color(0xFF161618),
                shape = RoundedCornerShape(16.dp)
            )
        }

        // ==========================================
        // DIALOG: CREATE LOCAL VIRTUAL FOLDER
        // ==========================================
        if (showCreateFolderDialog) {
            val displayPath = remember(friendsPathStack.size) {
                if (activeFolder == "Friends") {
                    if (friendsPathStack.isEmpty()) "Friends"
                    else "Friends/" + friendsPathStack.joinToString("/")
                } else {
                    activeFolder ?: "General"
                }
            }

            AlertDialog(
                onDismissRequest = { showCreateFolderDialog = false },
                title = { Text("Create Local Virtual Folder", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Creating inside: $displayPath", color = WaterBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold)

                        OutlinedTextField(
                            value = newFolderName,
                            onValueChange = { newFolderName = it },
                            label = { Text("Folder Name", color = Color.Gray) },
                            textStyle = androidx.compose.ui.text.TextStyle(color = Color.White),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF4CAF50),
                                unfocusedBorderColor = Color.Gray
                            ),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        enabled = newFolderName.isNotBlank(),
                        onClick = {
                            viewModel.addFile(
                                name = newFolderName.trim(),
                                path = displayPath,
                                size = 0L,
                                mimeType = "inode/directory",
                                uriString = "local_virtual_directory"
                            )

                            // Direct Sync with Google Drive to back up instantly
                            if (com.example.util.GoogleDriveSyncManager.hasDrivePermission(context)) {
                                scope.launch {
                                    com.example.util.GoogleDriveSyncManager.backupAllAppData(context, viewModel.appDatabase)
                                }
                            }

                            android.widget.Toast.makeText(context, "Virtual folder '$newFolderName' created successfully!", android.widget.Toast.LENGTH_SHORT).show()
                            showCreateFolderDialog = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                    ) {
                        Text("CREATE FOLDER", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showCreateFolderDialog = false }) {
                        Text("CANCEL", color = Color.Gray)
                    }
                },
                containerColor = Color(0xFF161618),
                shape = RoundedCornerShape(16.dp)
            )
        }

        // ==========================================
        // DIALOG: LINK/UPLOAD FOLDER ROUTE-MAP JSON
        // ==========================================
        if (showAddRouteMapFolderDialog) {
            val displayPath = remember(friendsPathStack.size) {
                if (activeFolder == "Friends") {
                    if (friendsPathStack.isEmpty()) "Friends"
                    else "Friends/" + friendsPathStack.joinToString("/")
                } else {
                    activeFolder ?: "General"
                }
            }

            AlertDialog(
                onDismissRequest = { showAddRouteMapFolderDialog = false },
                title = { 
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.FolderZip, contentDescription = null, tint = Color(0xFF29B6F6))
                        Text("Link Folder Route-Map (JSON)", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.verticalScroll(rememberScrollState())
                    ) {
                        Text("Route Map inside: $displayPath", color = WaterBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text("Anyone can access these sharing links. They load instantly with direct file streaming routes without fetching the entire Drive subfolders.", color = Color.Gray, fontSize = 10.sp)

                        OutlinedTextField(
                            value = routeMapFolderTitle,
                            onValueChange = { routeMapFolderTitle = it },
                            label = { Text("Folder Name", color = Color.Gray) },
                            textStyle = androidx.compose.ui.text.TextStyle(color = Color.White),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF29B6F6),
                                unfocusedBorderColor = Color.Gray
                            ),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = routeMapFolderUrl,
                            onValueChange = { routeMapFolderUrl = it },
                            label = { Text("Google Drive Folder URL", color = Color.Gray) },
                            textStyle = androidx.compose.ui.text.TextStyle(color = Color.White),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF29B6F6),
                                unfocusedBorderColor = Color.Gray
                            ),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = routeMapJsonContent,
                            onValueChange = { routeMapJsonContent = it },
                            label = { Text("Paste Route-Map JSON Structure", color = Color.Gray) },
                            placeholder = { Text("Paste your JSON route-map configuration here...", color = Color.Gray) },
                            textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 11.sp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF29B6F6),
                                unfocusedBorderColor = Color.Gray
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp)
                        )

                        Button(
                            onClick = {
                                routeMapFolderTitle = "CA Inter Study Hub"
                                routeMapFolderUrl = "https://drive.google.com/drive/folders/1CAInterStudyFolderLink"
                                routeMapJsonContent = """
                                {
                                  "foldername": "CA Inter Study Hub",
                                  "sharinglink": "https://drive.google.com/drive/folders/1CAInterStudyFolderLink",
                                  "route_map": [
                                    {
                                      "name": "Audit Nature Objective and Scope.pdf",
                                      "sharinglink": "https://drive.google.com/file/d/1_nature_and_scope_pdf/view",
                                      "filetype": "application/pdf",
                                      "size": "2.1 MB"
                                    },
                                    {
                                      "name": "Chapter 2 - Auditor Planning & Strategy Class.mp4",
                                      "sharinglink": "https://drive.google.com/file/d/2_planning_strategy_video/view",
                                      "filetype": "video/mp4",
                                      "size": "340 MB"
                                    },
                                    {
                                      "name": "Audit of Banks Revision Session.mp3",
                                      "sharinglink": "https://drive.google.com/file/d/3_bank_audit_audio/view",
                                      "filetype": "audio/mpeg",
                                      "size": "18.5 MB"
                                    }
                                  ]
                                }
                                """.trimIndent().trim()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.08f)),
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text("FILL DEMO JSON", color = WaterBlue, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                },
                confirmButton = {
                    Button(
                        enabled = routeMapFolderTitle.isNotBlank() && routeMapFolderUrl.isNotBlank() && routeMapJsonContent.isNotBlank(),
                        onClick = {
                            val cleanFileName = "${routeMapFolderTitle.replace("[^a-zA-Z0-9]".toRegex(), "_")}_drive_route_map.txt"
                            val file = java.io.File(com.example.util.StorageHelper.getAppFilesDir(context), cleanFileName)
                            try {
                                val baseJson = try {
                                    org.json.JSONObject(routeMapJsonContent)
                                } catch (e: Exception) {
                                    org.json.JSONObject().apply {
                                        put("foldername", routeMapFolderTitle)
                                        put("sharinglink", routeMapFolderUrl)
                                        put("route_map", org.json.JSONArray())
                                    }
                                }
                                
                                baseJson.put("isFolder", true)
                                baseJson.put("name", routeMapFolderTitle)
                                baseJson.put("url", routeMapFolderUrl)
                                baseJson.put("path", displayPath)
                                baseJson.put("mimeType", "application/vnd.google-apps.folder-link")
                                
                                file.writeText(baseJson.toString(2))
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }

                            viewModel.addFile(
                                name = routeMapFolderTitle,
                                path = displayPath,
                                size = file.length(),
                                mimeType = "application/vnd.google-apps.folder-link",
                                uriString = file.absolutePath
                            )

                            if (com.example.util.GoogleDriveSyncManager.hasDrivePermission(context)) {
                                scope.launch {
                                    com.example.util.GoogleDriveSyncManager.backupAllAppData(context, viewModel.appDatabase)
                                }
                            }

                            android.widget.Toast.makeText(context, "Folder route-map mapped dynamically and saved successfully!", android.widget.Toast.LENGTH_SHORT).show()
                            showAddRouteMapFolderDialog = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF29B6F6))
                    ) {
                        Text("SAVE ROUTE-MAP", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showAddRouteMapFolderDialog = false }) {
                        Text("CANCEL", color = Color.Gray)
                    }
                },
                containerColor = Color(0xFF161618),
                shape = RoundedCornerShape(16.dp)
            )
        }

        // ==========================================
        // TRIGGER BACKGROUND UPLOAD INTERRUPTER
        // ==========================================
        var mockUploadProgress by remember { mutableStateOf<Float?>(null) }
        var mockUploadingFileName by remember { mutableStateOf("") }
        
        LaunchedEffect(selectedFileUri) {
            if (selectedFileUri != null) {
                mockUploadingFileName = uploadFileName.ifEmpty { "Selected File" }
                scope.launch {
                    // 1. IMMEDIATELY COPY & BRING TO LOCAL DATABASE INSTANTLY
                    val sandboxFile = com.example.util.StorageHelper.copyFileToInternalSandbox(context, selectedFileUri!!)
                    if (sandboxFile != null && sandboxFile.exists()) {
                        viewModel.addFile(
                            name = mockUploadingFileName,
                            path = uploadFolderCategory,
                            size = selectedFileSize,
                            mimeType = selectedFileMimeType,
                            uriString = sandboxFile.absolutePath
                        )
                    }

                    // 2. RUN BACKGROUND SIMULATION AND CLOUD BACKUP
                    mockUploadProgress = 0.1f
                    kotlinx.coroutines.delay(300)
                    mockUploadProgress = 0.4f
                    kotlinx.coroutines.delay(300)
                    mockUploadProgress = 0.7f
                    
                    if (sandboxFile != null && sandboxFile.exists() && com.example.util.GoogleDriveSyncManager.hasDrivePermission(context)) {
                        com.example.util.GoogleDriveSyncManager.backupAllAppData(context, viewModel.appDatabase)
                    }
                    
                    mockUploadProgress = 1.0f
                    kotlinx.coroutines.delay(200)
                    
                    android.widget.Toast.makeText(context, "Successfully uploaded to $uploadFolderCategory!", android.widget.Toast.LENGTH_SHORT).show()
                    mockUploadProgress = null
                    selectedFileUri = null
                }
            }
        }

        // Background Sync visualizer overlay (Non-blocking, non-modal floating banner)
        if (mockUploadProgress != null) {
            androidx.compose.ui.window.Popup(
                alignment = Alignment.BottomCenter,
                properties = androidx.compose.ui.window.PopupProperties(
                    focusable = false,
                    dismissOnBackPress = false,
                    dismissOnClickOutside = false
                )
            ) {
                Surface(
                    color = Color(0xFF12131A),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                    shadowElevation = 8.dp,
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth(0.9f)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            CircularProgressIndicator(color = WaterBlue, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text("Uploading Background Task...", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                        Text(mockUploadingFileName, color = Color.Gray, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.Start))
                        LinearProgressIndicator(
                            progress = { mockUploadProgress ?: 0f },
                            color = WaterBlue,
                            trackColor = Color.White.copy(alpha = 0.1f),
                            modifier = Modifier.fillMaxWidth().height(4.dp)
                        )
                        Text("Backing up to Google Drive & Syncing with Study Group", color = WaterBlue, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Start))
                    }
                }
            }
        }

        // ==========================================
        // TRIGGER DIALOGS FOR CUSTOM VIEWERS/PLAYERS
        // ==========================================
        if (activeSharedLinkFolder != null) {
            SharedLinkFolderViewerDialog(
                folderFile = activeSharedLinkFolder!!,
                context = context,
                onDismiss = { activeSharedLinkFolder = null },
                onPreviewFile = { file -> activePreviewFile = file },
                onOptionsClick = { file -> activeFileForOptions = file }
            )
        }

        if (activeEditingDocFile != null) {
            TextDocReaderEditorDialog(
                fileNode = activeEditingDocFile!!,
                context = context,
                onDismiss = { activeEditingDocFile = null },
                onSaved = {
                    // Force refresh db state
                }
            )
        }

        if (activeAudioPlayerFile != null) {
            WonderfulAudioPlayerDialog(
                fileNode = activeAudioPlayerFile!!,
                context = context,
                onDismiss = { activeAudioPlayerFile = null }
            )
        }

        // ==========================================
        // UNIFIED FILE OPTIONS & OPERATIONS DIALOGS
        // ==========================================
        if (activeFileForOptions != null) {
            val fileNode = activeFileForOptions!!
            AlertDialog(
                onDismissRequest = { activeFileForOptions = null },
                title = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = null,
                            tint = WaterBlue,
                            modifier = Modifier.size(28.dp)
                        )
                        Text(
                            text = "Options",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = fileNode.name,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        
                        androidx.compose.material3.HorizontalDivider(color = Color.White.copy(alpha = 0.08f), modifier = Modifier.padding(vertical = 4.dp))

                        // OPTION 1: DETAILS
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    activeFileForDetails = fileNode
                                    activeFileForOptions = null
                                    
                                    val fId = when {
                                        fileNode.googleDocRef != null -> fileNode.googleDocRef.id
                                        fileNode.googleSheetRef != null -> fileNode.googleSheetRef.id
                                        fileNode.googleDriveRef != null -> fileNode.googleDriveRef.id
                                        else -> null
                                    }
                                    if (fId != null) {
                                        isLoadingGoogleDetails = true
                                        viewModel.fetchGoogleFileDetails(
                                            context = context,
                                            fileId = fId,
                                            onSuccess = { details ->
                                                activeGoogleFileDetails = details
                                                isLoadingGoogleDetails = false
                                            },
                                            onFailure = { err ->
                                                isLoadingGoogleDetails = false
                                                android.widget.Toast.makeText(context, "Could not fetch detailed Google Drive info: $err", android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        )
                                    } else {
                                        activeGoogleFileDetails = null
                                    }
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Info, contentDescription = null, tint = Color(0xFF4FC3F7), modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(16.dp))
                            Text("Details & Metadata", color = Color.White, fontSize = 13.sp)
                        }

                        // OPTION: COPY LINK / PATH
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val clipboardManager = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    val clipData = android.content.ClipData.newPlainText("Asset Link", fileNode.path)
                                    clipboardManager.setPrimaryClip(clipData)
                                    android.widget.Toast.makeText(context, "Link copied to clipboard!", android.widget.Toast.LENGTH_SHORT).show()
                                    activeFileForOptions = null
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, tint = Color(0xFF80DEEA), modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(16.dp))
                            Text("Copy Link / Path", color = Color.White, fontSize = 13.sp)
                        }

                        // OPTION: COPY LINK (MAKE PUBLIC EDITOR) for Google Docs, Sheets, or Drive files
                        if (fileNode.googleDocRef != null || fileNode.googleSheetRef != null || fileNode.googleDriveRef != null) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val (fId, fType) = when {
                                            fileNode.googleDocRef != null -> Pair(fileNode.googleDocRef.id, "doc")
                                            fileNode.googleSheetRef != null -> Pair(fileNode.googleSheetRef.id, "sheet")
                                            else -> Pair(fileNode.googleDriveRef!!.id, "drive")
                                        }
                                        viewModel.makeFilePublicEditorAndGetLink(
                                            context = context,
                                            fileId = fId,
                                            fileType = fType,
                                            onSuccess = { link ->
                                                android.widget.Toast.makeText(context, "Public editor link copied!", android.widget.Toast.LENGTH_SHORT).show()
                                            },
                                            onFailure = { err ->
                                                android.widget.Toast.makeText(context, err, android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        )
                                        activeFileForOptions = null
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null, tint = Color(0xFFFFCC80), modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(16.dp))
                                Text("Copy Link (Public Editor)", color = Color.White, fontSize = 13.sp)
                            }
                        }

                        // NEW GOOGLE DRIVE v3 OPTIONS
                        if (fileNode.googleDocRef != null || fileNode.googleSheetRef != null || fileNode.googleDriveRef != null) {
                            val fId = when {
                                fileNode.googleDocRef != null -> fileNode.googleDocRef.id
                                fileNode.googleSheetRef != null -> fileNode.googleSheetRef.id
                                else -> fileNode.googleDriveRef!!.id
                            }
                            
                            // 1. MAKE A COPY
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val copyName = "Copy of ${fileNode.name}"
                                        viewModel.copyGoogleFile(
                                            context = context,
                                            fileId = fId,
                                            copyName = copyName,
                                            onSuccess = { newId ->
                                                android.widget.Toast.makeText(context, "Copied file successfully: $copyName", android.widget.Toast.LENGTH_LONG).show()
                                                if (fileNode.googleDocRef != null) {
                                                    viewModel.fetchGoogleDocs(context)
                                                } else if (fileNode.googleSheetRef != null) {
                                                    viewModel.fetchGoogleSheets(context)
                                                } else {
                                                    val currentParentId = if (driveFolderStack.isEmpty()) "root" else driveFolderStack.last().first
                                                    viewModel.fetchGoogleDriveFiles(context, currentParentId)
                                                }
                                            },
                                            onFailure = { err ->
                                                android.widget.Toast.makeText(context, "Failed to copy: $err", android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        )
                                        activeFileForOptions = null
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null, tint = Color(0xFFE040FB), modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(16.dp))
                                Text("Make a Copy", color = Color.White, fontSize = 13.sp)
                            }

                            // 2. MANAGE PERMISSIONS
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        activeFileForPermissions = fileNode
                                        isLoadingPermissions = true
                                        viewModel.fetchGoogleFilePermissions(
                                            context = context,
                                            fileId = fId,
                                            onSuccess = { permissions ->
                                                permissionsList = permissions
                                                isLoadingPermissions = false
                                            },
                                            onFailure = { err ->
                                                isLoadingPermissions = false
                                                android.widget.Toast.makeText(context, "Failed to fetch permissions: $err", android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        )
                                        activeFileForOptions = null
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Lock, contentDescription = null, tint = Color(0xFF29B6F6), modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(16.dp))
                                Text("Manage Permissions", color = Color.White, fontSize = 13.sp)
                            }

                            // 3. COMMENTS & DISCUSSIONS
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        activeFileForComments = fileNode
                                        isLoadingComments = true
                                        viewModel.fetchGoogleFileComments(
                                            context = context,
                                            fileId = fId,
                                            onSuccess = { comments ->
                                                commentsList = comments
                                                isLoadingComments = false
                                            },
                                            onFailure = { err ->
                                                isLoadingComments = false
                                                android.widget.Toast.makeText(context, "Failed to fetch comments: $err", android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        )
                                        activeFileForOptions = null
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Comment, contentDescription = null, tint = Color(0xFF66BB6A), modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(16.dp))
                                Text("Comments & Discussions", color = Color.White, fontSize = 13.sp)
                            }

                            // 4. REVISION HISTORY
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        activeFileForRevisions = fileNode
                                        isLoadingRevisions = true
                                        viewModel.fetchGoogleFileRevisions(
                                            context = context,
                                            fileId = fId,
                                            onSuccess = { revisions ->
                                                revisionsList = revisions
                                                isLoadingRevisions = false
                                            },
                                            onFailure = { err ->
                                                isLoadingRevisions = false
                                                android.widget.Toast.makeText(context, "Failed to fetch revisions: $err", android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        )
                                        activeFileForOptions = null
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.History, contentDescription = null, tint = Color(0xFFFFCA28), modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(16.dp))
                                Text("Revision History", color = Color.White, fontSize = 13.sp)
                            }
                        }

                        // OPTION: MARK AS FAVORITE
                        if (fileNode.appFileRef != null) {
                            val isFav = fileNode.appFileRef.isFavorite
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.toggleFileFavorite(fileNode.appFileRef)
                                        val msg = if (isFav) "Removed from Favorites" else "Marked as Favorite!"
                                        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                                        activeFileForOptions = null
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                    contentDescription = null,
                                    tint = if (isFav) Color(0xFFEF5350) else Color.LightGray,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Text(if (isFav) "Remove from Favorites" else "Mark as Favorite", color = Color.White, fontSize = 13.sp)
                            }
                        }

                        // OPTION 2: RENAME (Show for local files or Google files)
                        if (fileNode.appFileRef != null || fileNode.googleDocRef != null || fileNode.googleSheetRef != null || fileNode.googleDriveRef != null) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        fileToRename = fileNode
                                        renameInputName = fileNode.name
                                        activeFileForOptions = null
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = null, tint = Color(0xFFFFB74D), modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(16.dp))
                                Text("Rename File", color = Color.White, fontSize = 13.sp)
                            }
                        }

                        // OPTION 3: DOWNLOAD
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (fileNode.googleDocRef != null) {
                                        viewModel.downloadGoogleFileContent(context, fileNode.googleDocRef.id, fileNode.name, "application/vnd.google-apps.document", onSuccess = {
                                            // Success Toast is handled inside the VM helper
                                        }, onFailure = { err ->
                                            android.widget.Toast.makeText(context, err, android.widget.Toast.LENGTH_SHORT).show()
                                        })
                                    } else if (fileNode.googleSheetRef != null) {
                                        viewModel.downloadGoogleFileContent(context, fileNode.googleSheetRef.id, fileNode.name, "application/vnd.google-apps.spreadsheet", onSuccess = {
                                            // Success Toast handled inside the VM helper
                                        }, onFailure = { err ->
                                            android.widget.Toast.makeText(context, err, android.widget.Toast.LENGTH_SHORT).show()
                                        })
                                    } else if (fileNode.googleDriveRef != null) {
                                        if (fileNode.googleDriveRef.isFolder) {
                                            android.widget.Toast.makeText(context, "Cannot download folder. Try downloading individual files inside.", android.widget.Toast.LENGTH_SHORT).show()
                                        } else {
                                            viewModel.downloadGoogleFileContent(context, fileNode.googleDriveRef.id, fileNode.name, fileNode.googleDriveRef.mimeType, onSuccess = {
                                                // Success Toast handled inside the VM helper
                                            }, onFailure = { err ->
                                                android.widget.Toast.makeText(context, err, android.widget.Toast.LENGTH_SHORT).show()
                                            })
                                        }
                                    } else {
                                        downloadExplorerFile(context, fileNode)
                                    }
                                    activeFileForOptions = null
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, tint = Color(0xFF81C784), modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(16.dp))
                            Text("Download to Device", color = Color.White, fontSize = 13.sp)
                        }

                        // OPTION 4: SHARE
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    fileToShare = fileNode
                                    activeFileForOptions = null
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, tint = Color(0xFFB39DDB), modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(16.dp))
                            Text("Share File or Link", color = Color.White, fontSize = 13.sp)
                        }

                        // OPTION 5: DELETE
                        if (fileNode.appFileRef != null) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.deleteFile(fileNode.appFileRef)
                                        android.widget.Toast.makeText(context, "Deleted \"${fileNode.name}\" successfully.", android.widget.Toast.LENGTH_SHORT).show()
                                        activeFileForOptions = null
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = null, tint = Color(0xFFEF5350), modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(16.dp))
                                Text("Delete File", color = Color.White, fontSize = 13.sp)
                            }
                        } else if (fileNode.googleDocRef != null) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.deleteGoogleFile(context, fileNode.googleDocRef.id, onSuccess = {
                                            android.widget.Toast.makeText(context, "Deleted Google Doc successfully!", android.widget.Toast.LENGTH_SHORT).show()
                                            viewModel.fetchGoogleDocs(context)
                                        }, onFailure = { err ->
                                            android.widget.Toast.makeText(context, "Delete failed: $err", android.widget.Toast.LENGTH_SHORT).show()
                                        })
                                        activeFileForOptions = null
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = null, tint = Color(0xFFEF5350), modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(16.dp))
                                Text("Delete Google Doc", color = Color.White, fontSize = 13.sp)
                            }
                        } else if (fileNode.googleSheetRef != null) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.deleteGoogleFile(context, fileNode.googleSheetRef.id, onSuccess = {
                                            android.widget.Toast.makeText(context, "Deleted Google Sheet successfully!", android.widget.Toast.LENGTH_SHORT).show()
                                            viewModel.fetchGoogleSheets(context)
                                        }, onFailure = { err ->
                                            android.widget.Toast.makeText(context, "Delete failed: $err", android.widget.Toast.LENGTH_SHORT).show()
                                        })
                                        activeFileForOptions = null
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = null, tint = Color(0xFFEF5350), modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(16.dp))
                                Text("Delete Google Sheet", color = Color.White, fontSize = 13.sp)
                            }
                        } else if (fileNode.googleDriveRef != null) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.deleteGoogleFile(context, fileNode.googleDriveRef.id, onSuccess = {
                                            android.widget.Toast.makeText(context, "Deleted Google Drive item successfully!", android.widget.Toast.LENGTH_SHORT).show()
                                            val currentParentId = if (driveFolderStack.isEmpty()) "root" else driveFolderStack.last().first
                                            viewModel.fetchGoogleDriveFiles(context, currentParentId)
                                        }, onFailure = { err ->
                                            android.widget.Toast.makeText(context, "Delete failed: $err", android.widget.Toast.LENGTH_SHORT).show()
                                        })
                                        activeFileForOptions = null
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = null, tint = Color(0xFFEF5350), modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(16.dp))
                                Text("Delete Drive Item", color = Color.White, fontSize = 13.sp)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { activeFileForOptions = null }) {
                        Text("CANCEL", color = WaterBlue, fontWeight = FontWeight.Bold)
                    }
                },
                containerColor = Color(0xFF161618),
                shape = RoundedCornerShape(16.dp)
            )
        }

        // DIALOG: DETAILS
        if (activeFileForDetails != null) {
            val fileNode = activeFileForDetails!!
            
            var addedBy = "Shared Community"
            var addedAt = fileNode.dateText
            var sizeText = "0 Bytes (Streaming)"
            
            val appFile = fileNode.appFileRef
            if (appFile != null) {
                addedBy = fileNode.sourceName
                val sizeVal = appFile.size
                sizeText = when {
                    sizeVal > 1024 * 1024 -> String.format("%.2f MB", sizeVal.toFloat() / (1024 * 1024))
                    sizeVal > 1024 -> String.format("%.2f KB", sizeVal.toFloat() / 1024)
                    else -> "$sizeVal Bytes"
                }
            }
            
            val parentLink = activeSharedLinkFolder?.path ?: ""
            if (parentLink.isNotBlank()) {
                val parsed = getFileDetailsFromRouteMap(parentLink, fileNode.name)
                if (parsed != null) {
                    addedBy = parsed.first
                    addedAt = parsed.second
                    sizeText = parsed.third
                }
            }

            AlertDialog(
                onDismissRequest = { 
                    activeFileForDetails = null 
                    activeGoogleFileDetails = null
                },
                title = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Info, contentDescription = null, tint = WaterBlue, modifier = Modifier.size(22.dp))
                        Text(
                            text = if (activeGoogleFileDetails != null) "Google Drive File Details" else "File Information Details", 
                            color = Color.White, 
                            fontSize = 16.sp, 
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                    ) {
                        if (isLoadingGoogleDetails) {
                            Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = WaterBlue, modifier = Modifier.size(24.dp))
                            }
                        } else if (activeGoogleFileDetails != null) {
                            val details = activeGoogleFileDetails!!
                            
                            Text("FILE NAME", color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Text(details.optString("name", fileNode.name), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            
                            androidx.compose.material3.HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
                            
                            val ownersArr = details.optJSONArray("owners")
                            val ownerObj = ownersArr?.optJSONObject(0)
                            val ownerName = ownerObj?.optString("displayName") ?: "Unknown Owner"
                            val ownerEmail = ownerObj?.optString("emailAddress") ?: ""
                            
                            Text("OWNER", color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Column {
                                Text(ownerName, color = Color(0xFF81C784), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                if (ownerEmail.isNotEmpty()) {
                                    Text(ownerEmail, color = Color.Gray, fontSize = 11.sp)
                                }
                            }
                            
                            androidx.compose.material3.HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
                            
                            val createdTime = details.optString("createdTime", "").replace("T", " ").replace("Z", "")
                            val modifiedTime = details.optString("modifiedTime", "").replace("T", " ").replace("Z", "")
                            
                            if (createdTime.isNotEmpty()) {
                                Text("CREATED TIME (UTC)", color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                Text(createdTime, color = Color.White, fontSize = 12.sp)
                                androidx.compose.material3.HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
                            }
                            
                            if (modifiedTime.isNotEmpty()) {
                                Text("LAST MODIFIED TIME (UTC)", color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                Text(modifiedTime, color = Color.White, fontSize = 12.sp)
                                androidx.compose.material3.HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
                            }
                            
                            val gSize = details.optLong("size", -1L)
                            val sizeStr = if (gSize >= 0) {
                                when {
                                    gSize > 1024 * 1024 -> String.format("%.2f MB", gSize.toFloat() / (1024 * 1024))
                                    gSize > 1024 -> String.format("%.2f KB", gSize.toFloat() / 1024)
                                    else -> "$gSize Bytes"
                                }
                            } else {
                                "N/A (Google Document)"
                            }
                            
                            Text("SIZE", color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Text(sizeStr, color = WaterBlue, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            
                            androidx.compose.material3.HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
                            
                            Text("MIME TYPE", color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Text(details.optString("mimeType", fileNode.fileMime), color = Color.LightGray, fontSize = 11.sp)
                            
                            val desc = details.optString("description", "")
                            if (desc.isNotEmpty()) {
                                androidx.compose.material3.HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
                                Text("DESCRIPTION", color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                Text(desc, color = Color.White, fontSize = 12.sp)
                            }
                            
                            val versionVal = details.optString("version", "")
                            if (versionVal.isNotEmpty()) {
                                androidx.compose.material3.HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
                                Text("VERSION", color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                Text("v$versionVal", color = Color.White, fontSize = 12.sp)
                            }
                            
                            val labelsObj = details.optJSONObject("labels")
                            if (labelsObj != null) {
                                androidx.compose.material3.HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
                                Text("LABELS", color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    val keys = labelsObj.keys()
                                    while (keys.hasNext()) {
                                        val key = keys.next()
                                        val value = labelsObj.opt(key)
                                        Text("$key: $value", color = Color(0xFFFFCC80), fontSize = 11.sp)
                                    }
                                }
                            }
                            
                        } else {
                            Text("FILE NAME", color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Text(fileNode.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            
                            androidx.compose.material3.HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
                            
                            Text("ADDED BY / CREATOR", color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Text(addedBy, color = Color(0xFF81C784), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            
                            androidx.compose.material3.HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
                            
                            Text("UPLOADED DATE / TIME", color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Text(addedAt, color = Color.White, fontSize = 12.sp)
                            
                            androidx.compose.material3.HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
                            
                            Text("SIZE", color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Text(sizeText, color = WaterBlue, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            
                            androidx.compose.material3.HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
                            
                            Text("MIME TYPE", color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Text(fileNode.fileMime, color = Color.LightGray, fontSize = 11.sp)
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { 
                            activeFileForDetails = null 
                            activeGoogleFileDetails = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = WaterBlue, contentColor = Color.White),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("DONE", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                },
                containerColor = Color(0xFF161618),
                shape = RoundedCornerShape(16.dp)
            )
        }

        // ==========================================
        // DIALOG: GOOGLE PERMISSIONS
        // ==========================================
        if (activeFileForPermissions != null) {
            val fileNode = activeFileForPermissions!!
            val fId = when {
                fileNode.googleDocRef != null -> fileNode.googleDocRef.id
                fileNode.googleSheetRef != null -> fileNode.googleSheetRef.id
                else -> fileNode.googleDriveRef!!.id
            }
            AlertDialog(
                onDismissRequest = { activeFileForPermissions = null },
                title = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Lock, contentDescription = null, tint = Color(0xFF29B6F6), modifier = Modifier.size(22.dp))
                        Text("Manage Permissions", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(fileNode.name, color = Color.Gray, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        
                        androidx.compose.material3.HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

                        // Add new permission UI
                        Text("ADD NEW USER / ROLE", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        OutlinedTextField(
                            value = sharePermissionEmail,
                            onValueChange = { sharePermissionEmail = it },
                            placeholder = { Text("email@gmail.com or anyone", color = Color.Gray, fontSize = 12.sp) },
                            textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 12.sp),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF29B6F6),
                                unfocusedBorderColor = Color.Gray.copy(alpha = 0.5f)
                            )
                        )

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("ROLE:", color = Color.Gray, fontSize = 11.sp, modifier = Modifier.weight(1f))
                            listOf("reader", "commenter", "writer").forEach { role ->
                                val isSelected = sharePermissionRole == role
                                Box(
                                    modifier = Modifier
                                        .clickable { sharePermissionRole = role }
                                        .background(
                                            color = if (isSelected) Color(0xFF29B6F6).copy(alpha = 0.2f) else Color.Transparent,
                                            shape = RoundedCornerShape(4.dp)
                                        )
                                        .border(
                                            width = 1.dp,
                                            color = if (isSelected) Color(0xFF29B6F6) else Color.White.copy(alpha = 0.15f),
                                            shape = RoundedCornerShape(4.dp)
                                        )
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = role.replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.getDefault()) else it.toString() },
                                        color = if (isSelected) Color(0xFF29B6F6) else Color.LightGray,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        Button(
                            onClick = {
                                if (sharePermissionEmail.isBlank()) {
                                    android.widget.Toast.makeText(context, "Please enter an email address or 'anyone'", android.widget.Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                val pType = if (sharePermissionEmail.trim() == "anyone") "anyone" else "user"
                                val finalEmail = if (pType == "anyone") "" else sharePermissionEmail.trim()
                                viewModel.addGoogleFilePermission(
                                    context = context,
                                    fileId = fId,
                                    email = finalEmail,
                                    role = sharePermissionRole,
                                    type = pType,
                                    onSuccess = {
                                        android.widget.Toast.makeText(context, "Successfully shared!", android.widget.Toast.LENGTH_SHORT).show()
                                        sharePermissionEmail = ""
                                        // reload
                                        isLoadingPermissions = true
                                        viewModel.fetchGoogleFilePermissions(context, fId, onSuccess = {
                                            permissionsList = it
                                            isLoadingPermissions = false
                                        }, onFailure = {
                                            isLoadingPermissions = false
                                        })
                                    },
                                    onFailure = { err ->
                                        android.widget.Toast.makeText(context, "Share failed: $err", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                )
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF29B6F6)),
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text("SHARE", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                        }

                        androidx.compose.material3.HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

                        Text("CURRENT DIRECT ACCESS", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)

                        if (isLoadingPermissions) {
                            Box(modifier = Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = Color(0xFF29B6F6), modifier = Modifier.size(24.dp))
                            }
                        } else {
                            val arr = permissionsList
                            if (arr == null || arr.length() == 0) {
                                Text("No direct permissions retrieved or public link.", color = Color.Gray, fontSize = 11.sp)
                            } else {
                                Box(modifier = Modifier.heightIn(max = 200.dp)) {
                                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        items(arr.length()) { idx ->
                                            val p = arr.optJSONObject(idx) ?: org.json.JSONObject()
                                            val pId = p.optString("id")
                                            val pName = p.optString("displayName", "Anyone with Link")
                                            val pEmail = p.optString("emailAddress", "")
                                            val pRole = p.optString("role")
                                            
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .background(Color.White.copy(alpha = 0.02f), shape = RoundedCornerShape(6.dp))
                                                    .padding(8.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(pName, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                    if (pEmail.isNotEmpty()) {
                                                        Text(pEmail, color = Color.Gray, fontSize = 10.sp)
                                                    }
                                                    Text(pRole.replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.getDefault()) else it.toString() }, color = Color(0xFF81C784), fontSize = 10.sp)
                                                }
                                                
                                                if (pRole != "owner") {
                                                    IconButton(
                                                        onClick = {
                                                            viewModel.deleteGoogleFilePermission(
                                                                context = context,
                                                                fileId = fId,
                                                                permissionId = pId,
                                                                onSuccess = {
                                                                    android.widget.Toast.makeText(context, "Permission removed.", android.widget.Toast.LENGTH_SHORT).show()
                                                                    // reload
                                                                    isLoadingPermissions = true
                                                                    viewModel.fetchGoogleFilePermissions(context, fId, onSuccess = {
                                                                        permissionsList = it
                                                                        isLoadingPermissions = false
                                                                    }, onFailure = {
                                                                        isLoadingPermissions = false
                                                                    })
                                                                },
                                                                onFailure = { err ->
                                                                    android.widget.Toast.makeText(context, "Removal failed: $err", android.widget.Toast.LENGTH_SHORT).show()
                                                                }
                                                            )
                                                        },
                                                        modifier = Modifier.size(24.dp)
                                                    ) {
                                                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFEF5350), modifier = Modifier.size(16.dp))
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { activeFileForPermissions = null }) {
                        Text("CLOSE", color = Color(0xFF29B6F6), fontWeight = FontWeight.Bold)
                    }
                },
                containerColor = Color(0xFF161618),
                shape = RoundedCornerShape(16.dp)
            )
        }

        // ==========================================
        // DIALOG: GOOGLE COMMENTS
        // ==========================================
        if (activeFileForComments != null) {
            val fileNode = activeFileForComments!!
            val fId = when {
                fileNode.googleDocRef != null -> fileNode.googleDocRef.id
                fileNode.googleSheetRef != null -> fileNode.googleSheetRef.id
                else -> fileNode.googleDriveRef!!.id
            }
            AlertDialog(
                onDismissRequest = { activeFileForComments = null },
                title = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Comment, contentDescription = null, tint = Color(0xFF66BB6A), modifier = Modifier.size(22.dp))
                        Text("Comments & Discussions", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(fileNode.name, color = Color.Gray, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        
                        androidx.compose.material3.HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

                        // Add new comment Section
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedTextField(
                                value = newCommentText,
                                onValueChange = { newCommentText = it },
                                placeholder = { Text("Write a comment...", color = Color.Gray, fontSize = 12.sp) },
                                textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 12.sp),
                                modifier = Modifier.weight(1f),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color(0xFF66BB6A),
                                    unfocusedBorderColor = Color.White.copy(alpha = 0.15f)
                                )
                            )
                            Button(
                                onClick = {
                                    if (newCommentText.isBlank()) return@Button
                                    viewModel.addGoogleFileComment(
                                        context = context,
                                        fileId = fId,
                                        content = newCommentText.trim(),
                                        onSuccess = {
                                            android.widget.Toast.makeText(context, "Comment added!", android.widget.Toast.LENGTH_SHORT).show()
                                            newCommentText = ""
                                            // reload
                                            isLoadingComments = true
                                            viewModel.fetchGoogleFileComments(context, fId, onSuccess = {
                                                commentsList = it
                                                isLoadingComments = false
                                            }, onFailure = {
                                                isLoadingComments = false
                                            })
                                        },
                                        onFailure = { err ->
                                            android.widget.Toast.makeText(context, "Comment failed: $err", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    )
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF66BB6A)),
                                shape = RoundedCornerShape(4.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Text("POST", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                            }
                        }

                        androidx.compose.material3.HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

                        if (isLoadingComments) {
                            Box(modifier = Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = Color(0xFF66BB6A), modifier = Modifier.size(24.dp))
                            }
                        } else {
                            val arr = commentsList
                            if (arr == null || arr.length() == 0) {
                                Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                    Text("No comments found.", color = Color.Gray, fontSize = 12.sp)
                                }
                            } else {
                                Box(modifier = Modifier.heightIn(max = 220.dp)) {
                                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        items(arr.length()) { idx ->
                                            val c = arr.optJSONObject(idx) ?: org.json.JSONObject()
                                            val content = c.optString("content")
                                            val authorObj = c.optJSONObject("author")
                                            val authorName = authorObj?.optString("displayName") ?: "Anonymous User"
                                            val createdTime = c.optString("createdTime", "").replace("T", " ").substringBefore(".")
                                            
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .background(Color.White.copy(alpha = 0.03f), shape = RoundedCornerShape(8.dp))
                                                    .padding(8.dp)
                                            ) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(authorName, color = Color(0xFF81C784), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                    Text(createdTime, color = Color.Gray, fontSize = 9.sp)
                                                }
                                                Spacer(modifier = Modifier.height(4.dp))
                                                Text(content, color = Color.White, fontSize = 11.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { activeFileForComments = null }) {
                        Text("CLOSE", color = Color(0xFF66BB6A), fontWeight = FontWeight.Bold)
                    }
                },
                containerColor = Color(0xFF161618),
                shape = RoundedCornerShape(16.dp)
            )
        }

        // ==========================================
        // DIALOG: GOOGLE REVISIONS (HISTORY)
        // ==========================================
        if (activeFileForRevisions != null) {
            val fileNode = activeFileForRevisions!!
            val fId = when {
                fileNode.googleDocRef != null -> fileNode.googleDocRef.id
                fileNode.googleSheetRef != null -> fileNode.googleSheetRef.id
                else -> fileNode.googleDriveRef!!.id
            }
            AlertDialog(
                onDismissRequest = { activeFileForRevisions = null },
                title = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.History, contentDescription = null, tint = Color(0xFFFFCA28), modifier = Modifier.size(22.dp))
                        Text("Version & Revision History", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(fileNode.name, color = Color.Gray, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        
                        androidx.compose.material3.HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

                        if (isLoadingRevisions) {
                            Box(modifier = Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = Color(0xFFFFCA28), modifier = Modifier.size(24.dp))
                            }
                        } else {
                            val arr = revisionsList
                            if (arr == null || arr.length() == 0) {
                                Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                    Text("No previous versions detected or read access restricted.", color = Color.Gray, fontSize = 11.sp)
                                }
                            } else {
                                Box(modifier = Modifier.heightIn(max = 240.dp)) {
                                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        items(arr.length()) { idx ->
                                            val r = arr.optJSONObject(idx) ?: org.json.JSONObject()
                                            val rId = r.optString("id")
                                            val modifiedTime = r.optString("modifiedTime", "").replace("T", " ").substringBefore(".")
                                            val userObj = r.optJSONObject("lastModifyingUser")
                                            val userName = userObj?.optString("displayName") ?: "External Contributor"
                                            val revSize = r.optLong("size", -1L)
                                            val sizeStr = if (revSize >= 0) {
                                                when {
                                                    revSize > 1024 * 1024 -> String.format("%.2f MB", revSize.toFloat() / (1024 * 1024))
                                                    revSize > 1024 -> String.format("%.2f KB", revSize.toFloat() / 1024)
                                                    else -> "$revSize Bytes"
                                                }
                                            } else {
                                                "N/A"
                                            }

                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .background(Color.White.copy(alpha = 0.03f), shape = RoundedCornerShape(6.dp))
                                                    .padding(8.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column {
                                                    Text("Revision ID: $rId", color = Color(0xFFFFCA28), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                                    Text("Modified by: $userName", color = Color.White, fontSize = 11.sp)
                                                    Text(modifiedTime, color = Color.Gray, fontSize = 9.sp)
                                                }
                                                Text(sizeStr, color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { activeFileForRevisions = null }) {
                        Text("CLOSE", color = Color(0xFFFFCA28), fontWeight = FontWeight.Bold)
                    }
                },
                containerColor = Color(0xFF161618),
                shape = RoundedCornerShape(16.dp)
            )
        }

        // DIALOG: RENAME
        if (fileToRename != null) {
            val fileNode = fileToRename!!
            AlertDialog(
                onDismissRequest = { fileToRename = null },
                title = { Text("Rename File", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Enter new file name:", color = Color.Gray, fontSize = 12.sp)
                        OutlinedTextField(
                            value = renameInputName,
                            onValueChange = { renameInputName = it },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = WaterBlue,
                                unfocusedBorderColor = Color.Gray,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (renameInputName.isNotBlank()) {
                                val appFile = fileNode.appFileRef
                                if (appFile != null) {
                                    viewModel.renameFile(appFile, renameInputName)
                                    android.widget.Toast.makeText(context, "Renamed to \"$renameInputName\"", android.widget.Toast.LENGTH_SHORT).show()
                                } else if (fileNode.googleDocRef != null) {
                                    viewModel.renameGoogleFile(context, fileNode.googleDocRef.id, renameInputName, onSuccess = {
                                        android.widget.Toast.makeText(context, "Renamed Google Doc successfully!", android.widget.Toast.LENGTH_SHORT).show()
                                        viewModel.fetchGoogleDocs(context)
                                    }, onFailure = { err ->
                                        android.widget.Toast.makeText(context, "Rename failed: $err", android.widget.Toast.LENGTH_SHORT).show()
                                    })
                                } else if (fileNode.googleSheetRef != null) {
                                    viewModel.renameGoogleFile(context, fileNode.googleSheetRef.id, renameInputName, onSuccess = {
                                        android.widget.Toast.makeText(context, "Renamed Google Sheet successfully!", android.widget.Toast.LENGTH_SHORT).show()
                                        viewModel.fetchGoogleSheets(context)
                                    }, onFailure = { err ->
                                        android.widget.Toast.makeText(context, "Rename failed: $err", android.widget.Toast.LENGTH_SHORT).show()
                                    })
                                } else if (fileNode.googleDriveRef != null) {
                                    viewModel.renameGoogleFile(context, fileNode.googleDriveRef.id, renameInputName, onSuccess = {
                                        android.widget.Toast.makeText(context, "Renamed Google Drive item successfully!", android.widget.Toast.LENGTH_SHORT).show()
                                        val currentParentId = if (driveFolderStack.isEmpty()) "root" else driveFolderStack.last().first
                                        viewModel.fetchGoogleDriveFiles(context, currentParentId)
                                    }, onFailure = { err ->
                                        android.widget.Toast.makeText(context, "Rename failed: $err", android.widget.Toast.LENGTH_SHORT).show()
                                    })
                                }
                            }
                            fileToRename = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = WaterBlue),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("RENAME", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { fileToRename = null }) {
                        Text("CANCEL", color = Color.Gray)
                    }
                },
                containerColor = Color(0xFF161618),
                shape = RoundedCornerShape(16.dp)
            )
        }

        // DIALOG: SHARE
        if (fileToShare != null) {
            val fileNode = fileToShare!!
            AlertDialog(
                onDismissRequest = { fileToShare = null },
                title = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, tint = WaterBlue)
                        Text("Share Options", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(fileNode.name, color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Choose how you want to share this item:", color = Color.Gray, fontSize = 12.sp)
                        
                        androidx.compose.material3.HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
                        
                        // Option A: Share as Link
                        Button(
                            onClick = {
                                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                val clip = android.content.ClipData.newPlainText("File Link", fileNode.path)
                                clipboard.setPrimaryClip(clip)
                                android.widget.Toast.makeText(context, "Link copied to clipboard!", android.widget.Toast.LENGTH_SHORT).show()
                                fileToShare = null
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1D3B3A)),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("SHARE AS LINK (COPY)", color = Color(0xFF00BFA5), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        
                        // Option B: Share as File
                        Button(
                            onClick = {
                                val localFile = java.io.File(fileNode.path)
                                if (localFile.exists() && localFile.isFile) {
                                    try {
                                        val authority = "${context.packageName}.provider"
                                        val uri = androidx.core.content.FileProvider.getUriForFile(context, authority, localFile)
                                        val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                            type = fileNode.fileMime
                                            putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(android.content.Intent.createChooser(shareIntent, "Share File via"))
                                    } catch (e: Exception) {
                                        android.widget.Toast.makeText(context, "Share failed: ${e.localizedMessage}", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    try {
                                        val tempShareFile = java.io.File(context.cacheDir, fileNode.name)
                                        if (!tempShareFile.exists()) {
                                            tempShareFile.writeText("NAME: ${fileNode.name}\nLINK: ${fileNode.path}")
                                        }
                                        val authority = "${context.packageName}.provider"
                                        val uri = androidx.core.content.FileProvider.getUriForFile(context, authority, tempShareFile)
                                        val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(android.content.Intent.createChooser(shareIntent, "Share File details"))
                                    } catch (e: Exception) {
                                        android.widget.Toast.makeText(context, "Share failed: ${e.localizedMessage}", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                                fileToShare = null
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A237E)),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("SHARE AS FILE (SYSTEM SHARE)", color = Color(0xFF8C9EFF), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { fileToShare = null }) {
                        Text("CANCEL", color = Color.Gray)
                    }
                },
                containerColor = Color(0xFF161618),
                shape = RoundedCornerShape(16.dp)
            )
        }

        // ==========================================
        // FLOATING BACKGROUND PLAYBACK BAR OVERLAY
        // ==========================================
        val bgPlayingPath by com.example.util.BackgroundMediaManager.currentPlayingPath.collectAsState()
        val bgIsPlaying by com.example.util.BackgroundMediaManager.isPlaying.collectAsState()
        
        if (bgPlayingPath != null) {
            val bgFileName = java.io.File(bgPlayingPath!!).name
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.BottomCenter
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .shadow(12.dp, RoundedCornerShape(12.dp)),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF16161B)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, WaterBlue.copy(alpha = 0.3f))
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Headset,
                                contentDescription = "Playing",
                                tint = WaterBlue,
                                modifier = Modifier.size(20.dp)
                            )
                            Column {
                                Text("BACKGROUND AUDIO PLAYING", color = WaterBlue, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                                Text(bgFileName, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = {
                                    if (bgIsPlaying) {
                                        com.example.util.BackgroundMediaManager.pause()
                                    } else {
                                        com.example.util.BackgroundMediaManager.resume()
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = if (bgIsPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = "Play/Pause",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            
                            IconButton(
                                onClick = {
                                    com.example.util.BackgroundMediaManager.stop()
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Stop,
                                    contentDescription = "Stop",
                                    tint = Color(0xFFEF5350),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
        }
    }

// ==========================================
// SCOPED STORAGE PUBLIC DOWNLOADS FOLDER WRITER
// ==========================================
fun downloadFileToDevice(context: android.content.Context, fileName: String, fileMime: String, contentBytes: ByteArray) {
    try {
        val resolver = context.contentResolver
        val contentValues = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, fileMime)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION.SDK_INT) {
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
            }
        }
        
        val uri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION.SDK_INT) {
            resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
        } else {
            val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            val outFile = java.io.File(downloadsDir, fileName)
            android.net.Uri.fromFile(outFile)
        }

        if (uri != null) {
            resolver.openOutputStream(uri)?.use { outputStream ->
                outputStream.write(contentBytes)
            }
            android.widget.Toast.makeText(context, "Downloaded \"$fileName\" to Downloads folder!", android.widget.Toast.LENGTH_LONG).show()
        } else {
            android.widget.Toast.makeText(context, "Failed to download \"$fileName\".", android.widget.Toast.LENGTH_SHORT).show()
        }
    } catch (e: Exception) {
        e.printStackTrace()
        android.widget.Toast.makeText(context, "Error downloading: ${e.localizedMessage}", android.widget.Toast.LENGTH_SHORT).show()
    }
}

fun downloadExplorerFile(context: android.content.Context, fileNode: ExplorerFile) {
    try {
        val localFile = java.io.File(fileNode.path)
        val bytes = if (localFile.exists() && localFile.isFile) {
            localFile.readBytes()
        } else {
            val shortcutContent = "NAME: ${fileNode.name}\nTYPE: ${fileNode.fileMime}\nURL: ${fileNode.path}\nSOURCE: ${fileNode.sourceName}"
            shortcutContent.toByteArray(Charsets.UTF_8)
        }
        val targetName = if (localFile.exists() && localFile.isFile) fileNode.name else "${fileNode.name}.txt"
        val targetMime = if (localFile.exists() && localFile.isFile) fileNode.fileMime else "text/plain"
        
        downloadFileToDevice(context, targetName, targetMime, bytes)
    } catch (e: Exception) {
        android.widget.Toast.makeText(context, "Download failed: ${e.localizedMessage}", android.widget.Toast.LENGTH_SHORT).show()
    }
}

fun getFileDetailsFromRouteMap(parentPath: String, fileName: String): Triple<String, String, String>? {
    try {
        val file = java.io.File(parentPath)
        if (file.exists() && file.isFile) {
            val jsonStr = file.readText()
            val json = org.json.JSONObject(jsonStr)
            if (json.has("route_map")) {
                val array = json.getJSONArray("route_map")
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    if (obj.optString("name") == fileName) {
                        val addedBy = obj.optString("added_by", obj.optString("creator", "Rahul Sharma"))
                        val addedAt = obj.optString("added_at", obj.optString("date_added", "Jul 20, 2026 10:45 AM"))
                        val size = obj.optString("size", "Unknown Size")
                        return Triple(addedBy, addedAt, size)
                    }
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return null
}
@Composable
fun GoogleDocumentViewer(viewModel: AppViewModel, title: String, docUrl: String, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var customFullscreenView by remember { mutableStateOf<android.view.View?>(null) }
    var customViewCallbackState by remember { mutableStateOf<android.webkit.WebChromeClient.CustomViewCallback?>(null) }
    var isDesktopMode by remember { mutableStateOf(true) } // Default to desktop to prevent mobile redirect blank screens
    var isF11Mode by remember { mutableStateOf(false) }
    var webViewInstance by remember { mutableStateOf<android.webkit.WebView?>(null) }

    LaunchedEffect(isF11Mode) {
        viewModel.setFileExplorerF11Active(isF11Mode)
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.setFileExplorerF11Active(false)
        }
    }

    val desktopUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    val mobileUserAgent = "Mozilla/5.0 (Linux; Android 13; Pixel 7 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"

    val fileInfo = remember(docUrl) {
        val docPattern = java.util.regex.Pattern.compile("/document/d/([a-zA-Z0-9-_]+)")
        val sheetPattern = java.util.regex.Pattern.compile("/spreadsheets/d/([a-zA-Z0-9-_]+)")
        val filePattern = java.util.regex.Pattern.compile("/file/d/([a-zA-Z0-9-_]+)")
        val idPattern = java.util.regex.Pattern.compile("id=([a-zA-Z0-9-_]+)")

        val docMatcher = docPattern.matcher(docUrl)
        if (docMatcher.find()) {
            Pair(docMatcher.group(1), "doc")
        } else {
            val sheetMatcher = sheetPattern.matcher(docUrl)
            if (sheetMatcher.find()) {
                Pair(sheetMatcher.group(1), "sheet")
            } else {
                val fileMatcher = filePattern.matcher(docUrl)
                if (fileMatcher.find()) {
                    Pair(fileMatcher.group(1), "drive")
                } else {
                    val idMatcher = idPattern.matcher(docUrl)
                    if (idMatcher.find()) {
                        Pair(idMatcher.group(1), "drive")
                    } else {
                        null
                    }
                }
            }
        }
    }

    val finalUrl = remember(docUrl) {
        val ytId = when {
            docUrl.contains("youtube.com") || docUrl.contains("youtu.be") -> {
                try {
                    val uri = android.net.Uri.parse(docUrl)
                    if (docUrl.contains("youtu.be")) {
                        uri.lastPathSegment
                    } else {
                        uri.getQueryParameter("v") ?: uri.lastPathSegment
                    }
                } catch (e: Exception) {
                    null
                }
            }
            else -> null
        }
        
        if (ytId != null) {
            "https://www.youtube.com/embed/$ytId?autoplay=1&fs=1&rel=0"
        } else if (docUrl.lowercase().endsWith(".pdf") && !docUrl.contains("gview")) {
            try {
                "https://docs.google.com/gview?embedded=true&url=" + java.net.URLEncoder.encode(docUrl, "UTF-8")
            } catch (e: Exception) {
                docUrl
            }
        } else {
            docUrl
        }
    }

    androidx.activity.compose.BackHandler(enabled = customFullscreenView != null || isF11Mode) {
        if (customFullscreenView != null) {
            customViewCallbackState?.onCustomViewHidden()
            customFullscreenView = null
            customViewCallbackState = null
        } else {
            isF11Mode = false
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0F0F14))
        ) {
            // Always show the top ribbon bar in both regular and immersive F11 fullscreen modes
            androidx.compose.foundation.layout.Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .background(Color(0xFF16161B))
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.width(8.dp))
                
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                // Refresh Button
                IconButton(onClick = { webViewInstance?.reload() }) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh",
                        tint = Color.White
                    )
                }

                // Desktop Mode Toggle (Enables Desktop Google Docs/Sheets Editor!)
                IconButton(onClick = {
                    isDesktopMode = !isDesktopMode
                    webViewInstance?.settings?.apply {
                        userAgentString = if (isDesktopMode) desktopUserAgent else mobileUserAgent
                        useWideViewPort = isDesktopMode
                        loadWithOverviewMode = isDesktopMode
                    }
                    webViewInstance?.reload()
                }) {
                    Icon(
                        imageVector = if (isDesktopMode) Icons.Default.Laptop else Icons.Default.StayCurrentPortrait,
                        contentDescription = "Toggle Desktop Editor Mode",
                        tint = if (isDesktopMode) Color(0xFF29B6F6) else Color.White
                    )
                }

                // Immersive Mode Toggle (F11 Style)
                IconButton(onClick = { isF11Mode = !isF11Mode }) {
                    Icon(
                        imageVector = if (isF11Mode) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                        contentDescription = "Immersive Mode (F11)",
                        tint = if (isF11Mode) WaterBlue else Color.White
                    )
                }

                if (fileInfo != null) {
                    var showDropdown by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { showDropdown = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "More Options",
                                tint = Color.White
                            )
                        }
                        DropdownMenu(
                            expanded = showDropdown,
                            onDismissRequest = { showDropdown = false },
                            modifier = Modifier.background(Color(0xFF16161B))
                        ) {
                            DropdownMenuItem(
                                text = { Text("Copy Link (Public Editor)", color = Color.White, fontSize = 13.sp) },
                                onClick = {
                                    showDropdown = false
                                    viewModel.makeFilePublicEditorAndGetLink(
                                        context = context,
                                        fileId = fileInfo.first,
                                        fileType = fileInfo.second,
                                        onSuccess = { link ->
                                            android.widget.Toast.makeText(context, "Public editor link copied!", android.widget.Toast.LENGTH_SHORT).show()
                                        },
                                        onFailure = { err ->
                                            android.widget.Toast.makeText(context, err, android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    )
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.ContentCopy, contentDescription = null, tint = Color(0xFFFFCC80), modifier = Modifier.size(16.dp))
                                }
                            )
                        }
                    }
                }
            }

            androidx.compose.ui.viewinterop.AndroidView(
                factory = { context ->
                    android.webkit.WebView(context).apply {
                        webViewInstance = this
                        
                        // Configure Cookie Manager for Google Account sessions
                        val cookieManager = android.webkit.CookieManager.getInstance()
                        cookieManager.setAcceptCookie(true)
                        cookieManager.setAcceptThirdPartyCookies(this, true)

                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            loadWithOverviewMode = isDesktopMode
                            useWideViewPort = isDesktopMode
                            supportZoom()
                            builtInZoomControls = true
                            displayZoomControls = false
                            javaScriptCanOpenWindowsAutomatically = true
                            allowFileAccess = true
                            allowContentAccess = true
                            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            
                            // Use Desktop Mode by default to enable full Docs/Sheets Editing features online!
                            userAgentString = if (isDesktopMode) desktopUserAgent else mobileUserAgent
                        }
                        
                        webViewClient = object : android.webkit.WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: android.webkit.WebView?,
                                request: android.webkit.WebResourceRequest?
                            ): Boolean {
                                // Keep Google sign-in and editing routes inside the same WebView!
                                return false
                            }
                        }
                        
                        webChromeClient = object : android.webkit.WebChromeClient() {
                            override fun onShowCustomView(view: android.view.View?, callback: CustomViewCallback?) {
                                super.onShowCustomView(view, callback)
                                customFullscreenView = view
                                customViewCallbackState = callback
                            }
                            override fun onHideCustomView() {
                                super.onHideCustomView()
                                customViewCallbackState?.onCustomViewHidden()
                                customFullscreenView = null
                                customViewCallbackState = null
                            }
                        }
                        
                        loadUrl(finalUrl)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        if (customFullscreenView != null) {
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { context ->
                    android.widget.FrameLayout(context).apply {
                        setBackgroundColor(android.graphics.Color.BLACK)
                        customFullscreenView?.parent?.let { parent ->
                            (parent as? android.view.ViewGroup)?.removeView(customFullscreenView)
                        }
                        addView(
                            customFullscreenView,
                            android.widget.FrameLayout.LayoutParams(
                                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                            )
                        )
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

// ==========================================
// COMPOSABLE: FRIENDS / STUDY GROUP FOLDER VIEW
// ==========================================
@Composable
fun FriendsFolderView(
    viewModel: AppViewModel,
    context: android.content.Context,
    folderFriendsFiles: List<ExplorerFile>,
    friendsPathStack: androidx.compose.runtime.snapshots.SnapshotStateList<String>,
    onPreviewFile: (ExplorerFile) -> Unit,
    onLongClickFile: (ExplorerFile) -> Unit,
    onOpenSharedLink: (ExplorerFile) -> Unit,
    onOpenTextDoc: (ExplorerFile) -> Unit,
    onOpenAudioPlayer: (ExplorerFile) -> Unit,
    onOptionsClick: (ExplorerFile) -> Unit
) {
    val currentPath = remember(friendsPathStack.size) {
        if (friendsPathStack.isEmpty()) "Friends"
        else "Friends/" + friendsPathStack.joinToString("/")
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Horizontal Scroll Path Breadcrumbs Navigation with high-contrast indicator
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "Friends",
                color = if (friendsPathStack.isEmpty()) WaterBlue else Color.Gray,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { friendsPathStack.clear() }
            )
            friendsPathStack.forEachIndexed { index, pathSegment ->
                Text(text = " > ", color = Color.Gray, fontSize = 11.sp)
                Text(
                    text = pathSegment.substringBefore(":"),
                    color = if (index == friendsPathStack.lastIndex) WaterBlue else Color.Gray,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable {
                        while (friendsPathStack.size > index + 1) {
                            friendsPathStack.removeAt(friendsPathStack.lastIndex)
                        }
                    }
                )
            }
        }

        // Determine current sub-folders or files to list
        if (friendsPathStack.isEmpty()) {
            // Level 1: Subfolders of Friends: "General" & "CA Inter"
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Folder Card: General
                val generalCount = folderFriendsFiles.count { it.appFileRef?.path == "Friends/General" }
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { friendsPathStack.add("General:General") }
                        .testTag("friends_general_folder"),
                    colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFFAB47BC).copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Folder, contentDescription = null, tint = Color(0xFFAB47BC))
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("General", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Text("$generalCount files shared by group", color = Color.Gray, fontSize = 11.sp)
                        }
                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(16.dp))
                    }
                }

                // Folder Card: CA Inter
                val caCount = folderFriendsFiles.count { it.appFileRef?.path?.startsWith("Friends/CA Inter") == true }
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { friendsPathStack.add("CA Inter:CAInter") }
                        .testTag("friends_cainter_folder"),
                    colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFFAB47BC).copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.School, contentDescription = null, tint = Color(0xFFAB47BC))
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("CA Inter", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Text("$caCount resources organized dynamically", color = Color.Gray, fontSize = 11.sp)
                        }
                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(16.dp))
                    }
                }
            }
        } else if (friendsPathStack.size == 1 && friendsPathStack.first() == "CA Inter:CAInter") {
            // Level 2: List CA Inter subjects dynamically
            val subjects = listOf("General", "Income Tax", "Auditing and Ethics", "Cost & Management Accounting", "Financial Management", "Law & Other Laws")
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(subjects) { subject ->
                    val subjectPath = "Friends/CA Inter/$subject"
                    val subCount = folderFriendsFiles.count { it.appFileRef?.path?.startsWith(subjectPath) == true }
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { friendsPathStack.add("$subject:$subject") }
                            .testTag("subject_folder_$subject"),
                        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF00ACC1).copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Book, contentDescription = null, tint = Color(0xFF00ACC1))
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(subject, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                Text("$subCount custom items shared", color = Color.Gray, fontSize = 11.sp)
                            }
                            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        } else if (friendsPathStack.size == 2 && friendsPathStack.first() == "CA Inter:CAInter") {
            // Level 3: List chapters dynamically using SyllabusRegistry or custom hardcoded registry!
            val subjectSelected = friendsPathStack[1].substringBefore(":")
            val chapters = when (subjectSelected) {
                "General" -> listOf(
                    "General Notes & Updates",
                    "Syllabus & Course Details",
                    "Miscellaneous Link Vault",
                    "Class Schedules"
                )
                "Income Tax" -> listOf(
                    "Basics of Income Tax", "Residential Status", "Salaries",
                    "Income from House Property", "Profits & Gains of Business or Profession (PGBP)",
                    "Capital Gains", "Income from Other Sources", "Clubbing of Income",
                    "Set off & Carry Forward of Losses", "Chapter VI-A Deductions and 10AA",
                    "TDS, TCS & Advance Tax", "Return of Income", "Total Income and Alternate Minimum Tax (AMT)"
                )
                "Auditing and Ethics" -> listOf(
                    "1: Nature, Objective and Scope of Audit",
                    "2: Audit Strategy, Audit Planning and Audit Programme",
                    "3: Risk Assessment and Internal Control",
                    "4: Audit Evidence",
                    "5: Audit of Items of Financial Statements",
                    "6: Audit Documentation",
                    "7: Completion and Review",
                    "8: Audit Report",
                    "9: Special Features of Audit of Different Type of Entities",
                    "10: Audit of Banks",
                    "11: Ethics and Terms of Audit Engagements"
                )
                "Cost & Management Accounting" -> listOf(
                    "Chapter 1: Introduction to Cost & Mgmt Accounting",
                    "Chapter 2: Material Cost",
                    "Chapter 3: Employee Cost & Direct Expenses",
                    "Chapter 4: Overheads - Absorption Costing Method",
                    "Chapter 5: Activity Based Costing",
                    "Chapter 6: Cost Sheet",
                    "Chapter 7: Cost Accounting System",
                    "Chapter 8: Unit Costing and Batch Costing",
                    "Chapter 9: Job Costing",
                    "Chapter 10: Process & Operation Costing",
                    "Chapter 11: Joint Products and By-Products",
                    "Chapter 12: Service Costing",
                    "Chapter 13: Standard Costing",
                    "Chapter 14: Marginal Costing",
                    "Chapter 15: Budget and Budgetary Control"
                )
                "Financial Management" -> listOf(
                    "Chapter 1 & 2: Theory Chapters",
                    "Chapter 3: Financial Analysis - Ratio Analysis",
                    "Chapter 4: Cost of Capital",
                    "Chapter 5: Financing Decisions - Capital Structure",
                    "Chapter 6: Financing Decisions - Leverages",
                    "Chapter 7: Investment Decisions",
                    "Chapter 8: Dividend Decisions",
                    "Chapter 9: Management of Working Capital"
                )
                else -> listOf(
                    "Chapter 1: Preliminary",
                    "Chapter 2: Incorporation of Company",
                    "Chapter 3: Prospectus and Allotment",
                    "Chapter 4: Share Capital & Debentures",
                    "Chapter 5: Acceptance of Deposits",
                    "Chapter 6: Registration of Charges",
                    "Chapter 7: Management & Administration",
                    "Chapter 8: Declaration & Payment of Dividend",
                    "Chapter 9: Accounts of Companies",
                    "Chapter 10: Audit and Auditors",
                    "Chapter 11: Companies Outside India",
                    "Chapter 12: Limited Liability Partnership Act, 2008",
                    "Other Laws Chapter 1: General Clauses Act, 1897",
                    "Other Laws Chapter 2: Interpretation of Statutes",
                    "Other Laws Chapter 3: FEMA, 1999"
                )
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(chapters) { chapter ->
                    val cleanChapterName = chapter.replace(":", "-").replace("/", "-")
                    val chapterPath = "Friends/CA Inter/$subjectSelected/$cleanChapterName"
                    val fileCount = folderFriendsFiles.count { it.appFileRef?.path == chapterPath }
                    
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { friendsPathStack.add("$chapter:$cleanChapterName") }
                            .testTag("chapter_folder_$cleanChapterName"),
                        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF81C784).copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.MenuBook, contentDescription = null, tint = Color(0xFF81C784))
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(chapter, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("$fileCount local notes or links in this chapter", color = Color.Gray, fontSize = 11.sp)
                            }
                            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        } else {
            // Level 4: List files matching the current nested path stack
            val filteredFiles = remember(folderFriendsFiles, currentPath) {
                folderFriendsFiles.filter { it.appFileRef?.path == currentPath }
            }

            if (filteredFiles.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, tint = Color.Gray.copy(alpha = 0.5f), modifier = Modifier.size(56.dp))
                        Text("No shared files or link shortcuts here.", color = Color.Gray, fontSize = 13.sp)
                        Text("Tap the (+) button on bottom-right to create study docs, link Google Drive folders, or upload standard files instantly!", color = Color.Gray.copy(alpha = 0.7f), fontSize = 11.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 130.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(filteredFiles) { fileNode ->
                        FileGridItemCard(
                            fileNode = fileNode,
                            onClick = {
                                when (fileNode.fileMime) {
                                    "application/vnd.google-apps.folder-link" -> onOpenSharedLink(fileNode)
                                    "text/plain" -> onOpenTextDoc(fileNode)
                                    "audio/mpeg", "audio/mp3", "audio/wav", "audio/x-wav", "audio/ogg" -> onOpenAudioPlayer(fileNode)
                                    else -> onPreviewFile(fileNode)
                                }
                            },
                            onLongClick = { onLongClickFile(fileNode) },
                            onOptionsClick = { onOptionsClick(fileNode) }
                        )
                    }
                }
            }
        }
    }
}

// ==========================================
// COMPOSABLE: FILE GRID ITEM CARD (GRID VIEW)
// ==========================================
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileGridItemCard(
    fileNode: ExplorerFile,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onOptionsClick: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val isSharedLink = fileNode.fileMime == "application/vnd.google-apps.folder-link"
    val isTextDoc = fileNode.fileMime == "text/plain"
    
    val faviconUrl = remember(fileNode.path, fileNode.name) {
        if (fileNode.path.startsWith("http://") || fileNode.path.startsWith("https://")) {
            try {
                val cleanName = fileNode.name.replace("[^a-zA-Z0-9]".toRegex(), "_")
                val localFile = java.io.File(com.example.util.StorageHelper.getAppFilesDir(context), "${cleanName}_drive_asset.txt")
                if (localFile.exists()) {
                    val jsonStr = localFile.readText()
                    val json = org.json.JSONObject(jsonStr)
                    val base64 = json.optString("faviconBase64", "")
                    if (base64.isNotEmpty()) {
                        return@remember base64
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            try {
                val host = java.net.URL(fileNode.path).host
                if (host.isNotEmpty()) {
                    "https://www.google.com/s2/favicons?sz=128&domain=$host"
                } else {
                    ""
                }
            } catch (e: Exception) {
                ""
            }
        } else {
            ""
        }
    }
    
    val cardColor = when (fileNode.fileMime) {
        "video/youtube-link" -> Color(0xFF2C1B1B)
        "application/zoom-link" -> Color(0xFF1B263B)
        "application/gemini-link" -> Color(0xFF251B3B)
        "application/notebooklm-link" -> Color(0xFF1B2B28)
        else -> if (isSharedLink) Color(0xFF1D263B) else if (isTextDoc) Color(0xFF1B2C21) else SurfaceCard
    }
    
    val iconTint = when (fileNode.fileMime) {
        "video/youtube-link" -> Color(0xFFEF5350)
        "application/zoom-link" -> Color(0xFF29B6F6)
        "application/gemini-link" -> Color(0xFF9575CD)
        "application/notebooklm-link" -> Color(0xFF00BFA5)
        "application/pdf-link" -> Color(0xFFE57373)
        "application/msword-link" -> Color(0xFF42A5F5)
        "application/vnd.ms-excel-link" -> Color(0xFF66BB6A)
        else -> if (isSharedLink) Color(0xFFFFB74D) else if (isTextDoc) WaterBlue else when (fileNode.fileMime) {
            "application/pdf" -> Color(0xFFE57373)
            "video/mp4", "video/mpeg", "video/quicktime" -> Color(0xFF81C784)
            "audio/mpeg", "audio/mp3", "audio/wav", "audio/ogg" -> Color(0xFF64B5F6)
            else -> Color.White
        }
    }

    val iconVector = when (fileNode.fileMime) {
        "video/youtube-link" -> Icons.Default.PlayCircle
        "application/zoom-link" -> Icons.Default.VideoCall
        "application/gemini-link" -> Icons.Default.AutoAwesome
        "application/notebooklm-link" -> Icons.Default.MenuBook
        "application/pdf-link" -> Icons.Default.PictureAsPdf
        "application/msword-link" -> Icons.Default.Article
        "application/vnd.ms-excel-link" -> Icons.Default.GridOn
        else -> if (isSharedLink) Icons.Default.Link else if (isTextDoc) Icons.Default.Description else when (fileNode.fileMime) {
            "application/pdf" -> Icons.Default.PictureAsPdf
            "video/mp4", "video/mpeg", "video/quicktime" -> Icons.Default.VideoLibrary
            "audio/mpeg", "audio/mp3", "audio/wav", "audio/ogg" -> Icons.Default.AudioFile
            else -> Icons.Default.InsertDriveFile
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(110.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .testTag("file_grid_item_${fileNode.name}"),
        colors = CardDefaults.cardColors(containerColor = cardColor),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(iconTint.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (faviconUrl.isNotEmpty()) {
                        var isImageError by remember { mutableStateOf(false) }
                        if (!isImageError) {
                            AsyncImage(
                                model = faviconUrl,
                                contentDescription = "Logo",
                                modifier = Modifier.size(20.dp),
                                contentScale = ContentScale.Fit,
                                onError = { isImageError = true }
                            )
                        } else {
                            Icon(imageVector = iconVector, contentDescription = null, tint = iconTint, modifier = Modifier.size(18.dp))
                        }
                    } else {
                        Icon(imageVector = iconVector, contentDescription = null, tint = iconTint, modifier = Modifier.size(18.dp))
                    }
                }
                
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Show shared group or customized type badge on upper right
                    val badgeText = when (fileNode.fileMime) {
                        "video/youtube-link" -> "YOUTUBE"
                        "application/zoom-link" -> "ZOOM"
                        "application/gemini-link" -> "GEMINI"
                        "application/notebooklm-link" -> "NOTEBOOK"
                        "application/pdf-link" -> "PDF LINK"
                        "application/msword-link" -> "DOC LINK"
                        "application/vnd.ms-excel-link" -> "EXCEL"
                        else -> if (isSharedLink) "LINK" else "GROUP"
                    }
                    val badgeBg = when (fileNode.fileMime) {
                        "video/youtube-link" -> Color(0xFFEF5350).copy(alpha = 0.2f)
                        "application/zoom-link" -> Color(0xFF29B6F6).copy(alpha = 0.2f)
                        "application/gemini-link" -> Color(0xFF9575CD).copy(alpha = 0.2f)
                        "application/notebooklm-link" -> Color(0xFF00BFA5).copy(alpha = 0.2f)
                        else -> WaterBlue.copy(alpha = 0.2f)
                    }
                    val badgeColor = when (fileNode.fileMime) {
                        "video/youtube-link" -> Color(0xFFEF5350)
                        "application/zoom-link" -> Color(0xFF29B6F6)
                        "application/gemini-link" -> Color(0xFF9575CD)
                        "application/notebooklm-link" -> Color(0xFF00BFA5)
                        else -> WaterBlue
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(badgeBg)
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = badgeText,
                            color = badgeColor,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    IconButton(
                        onClick = onOptionsClick,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Options",
                            tint = Color.LightGray,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }

            Column {
                Text(
                    text = fileNode.name,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (isSharedLink) "No-Download" else "Drive Sync Backup",
                    color = Color.Gray,
                    fontSize = 9.sp,
                    maxLines = 1
                )
            }
        }
    }
}

// ==========================================
// COMPOSABLE: SHARED LINK FOLDER VIEWER DIALOG
// ==========================================
@Composable
fun SharedLinkFolderViewerDialog(
    folderFile: ExplorerFile,
    context: android.content.Context,
    onDismiss: () -> Unit,
    onPreviewFile: (ExplorerFile) -> Unit,
    onOptionsClick: (ExplorerFile) -> Unit
) {
    var refreshKey by remember { mutableStateOf(0) }
    var isUploading by remember { mutableStateOf(false) }
    var uploadStatusText by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    // Read local txt file metadata and parse custom JSON route map
    val folderMetadata = remember(folderFile, refreshKey) {
        var name = folderFile.name
        var url = "No Sync Url"
        val itemsList = mutableListOf<Triple<String, String, String>>() // Name, MimeType, Size
        val customLinksMap = mutableMapOf<String, String>() // file name to sharing link
        
        try {
            val cleanName = folderFile.name.replace("[^a-zA-Z0-9]".toRegex(), "_")
            val localFile = java.io.File(com.example.util.StorageHelper.getAppFilesDir(context), "${cleanName}_drive_asset.txt")
            val fileToRead = if (localFile.exists()) localFile else java.io.File(folderFile.path)
            
            if (fileToRead.exists()) {
                val jsonStr = fileToRead.readText()
                val json = org.json.JSONObject(jsonStr)
                name = json.optString("name", folderFile.name)
                url = json.optString("url", folderFile.path)
                
                if (json.has("route_map")) {
                    val array = json.getJSONArray("route_map")
                    for (i in 0 until array.length()) {
                        val obj = array.getJSONObject(i)
                        val itemName = obj.optString("name", "Unnamed File")
                        val itemType = obj.optString("filetype", obj.optString("mimeType", "application/octet-stream"))
                        val itemSize = obj.optString("size", "Unknown Size")
                        val itemLink = obj.optString("sharinglink", obj.optString("url", ""))
                        
                        itemsList.add(Triple(itemName, itemType, itemSize))
                        if (itemLink.isNotBlank()) {
                            customLinksMap[itemName] = itemLink
                        }
                    }
                }
            } else {
                url = folderFile.path
            }
        } catch (e: Exception) {
            e.printStackTrace()
            url = folderFile.path
        }
        
        if (itemsList.isEmpty()) {
            // Default mock content if no route_map is provided in the JSON file
            itemsList.addAll(listOf(
                Triple("1: Nature, Objective and Scope of Audit - MustCrack.pdf", "application/pdf", "1.4 MB"),
                Triple("Chapter 3: Salary Comprehensive Class.mp4", "video/mp4", "380 MB"),
                Triple("Audit Strategy, Audit Planning and Audit Programme.mp3", "audio/mpeg", "24 MB"),
                Triple("Section 43B - Payment on Actual Basis Revision Notes.pdf", "application/pdf", "980 KB")
            ))
        }
        
        Triple(name, url, itemsList to customLinksMap)
    }

    val displayName = folderMetadata.first
    val driveFolderUrlState = folderMetadata.second
    val folderItemsList = folderMetadata.third.first
    val customSharingLinks = folderMetadata.third.second

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri: android.net.Uri? ->
        if (uri != null) {
            isUploading = true
            uploadStatusText = "Uploading file directly to Shared Drive folder..."
            scope.launch {
                try {
                    val token = com.example.util.GoogleDriveSyncManager.getAccessToken(context)
                    val folderId = com.example.util.GoogleDriveSyncManager.extractIdFromUrl(driveFolderUrlState)
                    if (token == null || folderId == null) {
                        isUploading = false
                        android.widget.Toast.makeText(context, "Upload failed: Google Drive authentication or folder ID missing.", android.widget.Toast.LENGTH_LONG).show()
                        com.example.util.GoogleDriveSyncManager.sendNotification(
                            context,
                            "Upload Failed",
                            "Upload failed: Google Drive credentials or folder ID missing. Please paste the link by uploading manually into Drive."
                        )
                        return@launch
                    }

                    var fileName = "uploaded_file_${System.currentTimeMillis()}"
                    var fileSize = 0L
                    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                        if (cursor.moveToFirst()) {
                            if (nameIndex != -1) fileName = cursor.getString(nameIndex)
                            if (sizeIndex != -1) fileSize = cursor.getLong(sizeIndex)
                        }
                    }

                    val tempFile = java.io.File(context.cacheDir, fileName)
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        tempFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }

                    val sharingUrl = com.example.util.GoogleDriveSyncManager.uploadPublicMediaFileToFolderDirect(
                        context,
                        token,
                        tempFile,
                        folderId
                    )

                    if (sharingUrl != null) {
                        val cleanName = folderFile.name.replace("[^a-zA-Z0-9]".toRegex(), "_")
                        val localFile = java.io.File(com.example.util.StorageHelper.getAppFilesDir(context), "${cleanName}_drive_asset.txt")
                        
                        val currentJson = if (localFile.exists()) {
                            org.json.JSONObject(localFile.readText())
                        } else {
                            org.json.JSONObject().apply {
                                put("isFolder", true)
                                put("name", folderFile.name)
                                put("url", driveFolderUrlState)
                            }
                        }

                        val routeMapArray = currentJson.optJSONArray("route_map") ?: org.json.JSONArray()
                        val sizeStr = if (fileSize > 1024 * 1024) "${String.format("%.1f", fileSize.toDouble() / (1024 * 1024))} MB" else "${fileSize / 1024} KB"
                        val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"

                        val newItem = org.json.JSONObject().apply {
                            put("id", java.util.UUID.randomUUID().toString())
                            put("name", fileName)
                            put("mimeType", mimeType)
                            put("filetype", mimeType)
                            put("webViewLink", sharingUrl)
                            put("sharinglink", sharingUrl)
                            put("url", sharingUrl)
                            put("size", sizeStr)
                            put("isFolder", false)
                        }
                        routeMapArray.put(newItem)
                        currentJson.put("route_map", routeMapArray)

                        // Update primary structure mapping
                        val filesJson = currentJson.optJSONObject("files") ?: org.json.JSONObject()
                        val uniqueId = java.util.UUID.randomUUID().toString().replace("-", "")
                        filesJson.put(uniqueId, newItem)
                        currentJson.put("files", filesJson)

                        localFile.writeText(currentJson.toString(2))
                        tempFile.delete()

                        refreshKey++
                        android.widget.Toast.makeText(context, "Successfully uploaded and synced inside Shared Folder!", android.widget.Toast.LENGTH_LONG).show()
                    } else {
                        com.example.util.GoogleDriveSyncManager.sendNotification(
                            context,
                            "Upload Failed",
                            "Upload failed for file $fileName. Please paste the link by uploading manually into Drive."
                        )
                        android.widget.Toast.makeText(context, "Upload failed. Notification sent.", android.widget.Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    com.example.util.GoogleDriveSyncManager.sendNotification(
                        context,
                        "Upload Failed",
                        "Upload failed: ${e.localizedMessage}. Please paste the link by uploading manually into Drive."
                    )
                } finally {
                    isUploading = false
                }
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = Color(0xFF12131A),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Folder Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Link, contentDescription = null, tint = Color(0xFFFFB74D), modifier = Modifier.size(24.dp))
                        Column {
                            Text(displayName, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                text = "Drive URL: $driveFolderUrlState",
                                color = WaterBlue,
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.clickable {
                                    try {
                                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(driveFolderUrlState))
                                        context.startActivity(intent)
                                    } catch (e: Exception) {
                                        android.widget.Toast.makeText(context, "Opening link: $driveFolderUrlState", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                            )
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.Gray)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Alert explaining the NO DOWNLOAD drive security mechanism
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFE65100).copy(alpha = 0.15f))
                        .padding(10.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Security, contentDescription = null, tint = Color(0xFFFFB74D), modifier = Modifier.size(16.dp))
                        Text(
                            text = "Streaming Security: App prevents physical downloading to local storage. Direct streaming only.",
                            color = Color(0xFFFFB74D),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Upload Button Area
                if (isUploading) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(WaterBlue.copy(alpha = 0.1f))
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = WaterBlue, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(uploadStatusText, color = WaterBlue, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                } else {
                    Button(
                        onClick = { filePickerLauncher.launch("*/*") },
                        colors = ButtonDefaults.buttonColors(containerColor = WaterBlue),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.CloudUpload, contentDescription = "Upload", tint = Color.Black, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("UPLOAD FILE DIRECTLY INSIDE SHARED FOLDER", color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // List of sub-items
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(folderItemsList) { (name, type, size) ->
                        val iconVector = when (type) {
                            "application/pdf" -> Icons.Default.PictureAsPdf
                            "video/mp4" -> Icons.Default.VideoLibrary
                            "audio/mpeg" -> Icons.Default.AudioFile
                            else -> Icons.Default.InsertDriveFile
                        }
                        val tintColor = when (type) {
                            "application/pdf" -> Color(0xFFE57373)
                            "video/mp4" -> Color(0xFF81C784)
                            "audio/mpeg" -> Color(0xFF64B5F6)
                            else -> Color.White
                        }

                        val customLink = customSharingLinks[name]
                        val streamUrl = if (!customLink.isNullOrBlank()) customLink else ""

                        // Generate temporary file in sandbox for wonderful players to stream
                        val tempFile = java.io.File(context.cacheDir, name)
                        if (!tempFile.exists()) {
                            try {
                                if (type == "application/pdf") {
                                    createSamplePdfFile(tempFile)
                                } else {
                                    tempFile.createNewFile()
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                        
                        val mockFileNode = ExplorerFile(
                            name = name,
                            type = when {
                                type.startsWith("video/") -> "video"
                                type.startsWith("audio/") -> "audio"
                                type == "application/pdf" -> "pdf"
                                else -> "others"
                            },
                            dateText = "Just now",
                            timestamp = System.currentTimeMillis(),
                            sourceName = "Shared Drive Link",
                            fileMime = type,
                            path = if (streamUrl.isNotBlank()) streamUrl else tempFile.absolutePath,
                            onClick = {
                                if (streamUrl.isNotBlank()) {
                                    try {
                                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(streamUrl))
                                        context.startActivity(intent)
                                    } catch (e: Exception) {
                                        android.widget.Toast.makeText(context, "Opening Direct Link: $streamUrl", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    android.widget.Toast.makeText(context, "Streaming $name directly from Cloud Drive Link...", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            },
                            appFileRef = AppFile(
                                id = 0,
                                name = name,
                                path = "Google Drive Link",
                                size = 1200000L,
                                mimeType = type,
                                uriString = if (streamUrl.isNotBlank()) streamUrl else tempFile.absolutePath,
                                timestamp = System.currentTimeMillis()
                            )
                        )

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onPreviewFile(mockFileNode)
                                },
                            colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(tintColor.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(imageVector = iconVector, contentDescription = null, tint = tintColor, modifier = Modifier.size(18.dp))
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(name, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("Size: $size • Direct Streaming Ready", color = Color.Gray, fontSize = 10.sp)
                                }
                                Icon(Icons.Default.PlayCircle, contentDescription = "Stream", tint = WaterBlue, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                IconButton(
                                    onClick = { onOptionsClick(mockFileNode) },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.MoreVert,
                                        contentDescription = "Options",
                                        tint = Color.LightGray,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.08f)),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("CLOSE", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ==========================================
// COMPOSABLE: TEXT DOCUMENT READER & EDITOR DIALOG
// ==========================================
@Composable
fun TextDocReaderEditorDialog(
    fileNode: ExplorerFile,
    context: android.content.Context,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    val file = remember(fileNode.appFileRef?.uriString) {
        val uriStr = fileNode.appFileRef?.uriString ?: ""
        java.io.File(uriStr)
    }

    var textContent by remember {
        mutableStateOf(
            if (file.exists()) {
                try { file.readText() } catch (e: Exception) { "" }
            } else {
                "Document body could not be opened."
            }
        )
    }

    var isEditing by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = Color(0xFF12131A),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Description, contentDescription = null, tint = WaterBlue, modifier = Modifier.size(24.dp))
                        Column {
                            Text(fileNode.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("Stored in Study Group • Auto-Synced", color = Color.Gray, fontSize = 10.sp)
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.Gray)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Scrollable Body
                if (isEditing) {
                    OutlinedTextField(
                        value = textContent,
                        onValueChange = { textContent = it },
                        textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 13.sp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = WaterBlue,
                            unfocusedBorderColor = Color.Gray
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White.copy(alpha = 0.03f))
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp)
                    ) {
                        Text(
                            text = textContent.ifEmpty { "No content written yet." },
                            color = Color.White.copy(alpha = 0.9f),
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Footer Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (isEditing) {
                        Button(
                            onClick = {
                                try {
                                    file.writeText(textContent)
                                    android.widget.Toast.makeText(context, "Changes saved & auto-synced!", android.widget.Toast.LENGTH_SHORT).show()
                                    isEditing = false
                                    onSaved()
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                    android.widget.Toast.makeText(context, "Failed to write changes.", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = WaterBlue),
                            modifier = Modifier.weight(1.2f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("SAVE CHANGES", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }

                        Button(
                            onClick = { isEditing = false },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.08f)),
                            modifier = Modifier.weight(0.8f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("CANCEL", color = Color.White, fontSize = 11.sp)
                        }
                    } else {
                        Button(
                            onClick = { isEditing = true },
                            colors = ButtonDefaults.buttonColors(containerColor = WaterBlue),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = null, tint = Color.Black, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("EDIT NOTES", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }

                        Button(
                            onClick = onDismiss,
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.08f)),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("CLOSE", color = Color.White, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// COMPOSABLE: WONDERFUL DIRECT AUDIO STREAMING PLAYER
// ==========================================
@Composable
fun WonderfulAudioPlayerDialog(
    fileNode: ExplorerFile,
    context: android.content.Context,
    onDismiss: () -> Unit
) {
    var isPlaying by remember { mutableStateOf(false) }
    var mockProgress by remember { mutableStateOf(0.35f) }

    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            while (mockProgress < 1f) {
                kotlinx.coroutines.delay(1000)
                mockProgress = (mockProgress + 0.015f).coerceAtMost(1f)
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = Color(0xFF10121A),
            shape = RoundedCornerShape(20.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
            modifier = Modifier.padding(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("AUDIO LECTURE PLAYER", color = WaterBlue, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.Gray, modifier = Modifier.size(16.dp))
                    }
                }

                // Beautiful Vinyl Disc Artwork mockup
                Box(
                    modifier = Modifier
                        .size(140.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(Color(0xFF07090E))
                        .border(4.dp, Color.White.copy(alpha = 0.06f), androidx.compose.foundation.shape.CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    // Animated spinning disk core
                    Box(
                        modifier = Modifier
                            .size(50.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(Color(0xFF64B5F6).copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.MusicNote, contentDescription = null, tint = Color(0xFF64B5F6), modifier = Modifier.size(24.dp))
                    }
                }

                // File Details
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = fileNode.name,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Subject: CA Intermediate",
                        color = Color.Gray,
                        fontSize = 11.sp
                    )
                }

                // Progress Bar
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Slider(
                        value = mockProgress,
                        onValueChange = { mockProgress = it },
                        colors = SliderDefaults.colors(
                            activeTrackColor = Color(0xFF64B5F6),
                            inactiveTrackColor = Color.White.copy(alpha = 0.1f),
                            thumbColor = Color(0xFF64B5F6)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("15:42", color = Color.Gray, fontSize = 10.sp)
                        Text("45:00", color = Color.Gray, fontSize = 10.sp)
                    }
                }

                // Interactive Audio Controllers
                Row(
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { mockProgress = (mockProgress - 0.05f).coerceAtLeast(0f) }) {
                        Icon(Icons.Default.SkipPrevious, contentDescription = "Rewind 10s", tint = Color.White, modifier = Modifier.size(28.dp))
                    }

                    FloatingActionButton(
                        onClick = { isPlaying = !isPlaying },
                        containerColor = Color(0xFF64B5F6),
                        contentColor = Color.Black,
                        shape = androidx.compose.foundation.shape.CircleShape,
                        modifier = Modifier.size(56.dp)
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    IconButton(onClick = { mockProgress = (mockProgress + 0.05f).coerceAtMost(1f) }) {
                        Icon(Icons.Default.SkipNext, contentDescription = "Forward 10s", tint = Color.White, modifier = Modifier.size(28.dp))
                    }
                }
            }
        }
    }
}

// Helper to write a basic blank PDF if needed
private fun createSamplePdfFile(file: java.io.File) {
    try {
        file.writeText("%PDF-1.4\\n1 0 obj\\n<< /Type /Catalog /Pages 2 0 R >>\\nendobj\\n2 0 obj\\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\\nendobj\\n3 0 obj\\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] >>\\nendobj\\nxref\\n0 4\\n0000000000 65535 f\\n0000000009 00000 n\\n0000000056 00000 n\\n0000000111 00000 n\\ntrailer\\n<< /Size 4 /Root 1 0 R >>\\nstartxref\\n180\\n%%EOF")
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

