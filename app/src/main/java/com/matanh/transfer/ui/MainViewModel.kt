package com.matanh.transfer.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.matanh.transfer.R
import com.matanh.transfer.util.Constants
import com.matanh.transfer.util.FileItem
import com.matanh.transfer.util.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _files = MutableLiveData<List<FileItem>>()
    val files: LiveData<List<FileItem>> = _files

    private val _selectedFolderUri = MutableLiveData<Uri?>()
    val selectedFolderUri: LiveData<Uri?> = _selectedFolderUri

    init {
        // Load the initial URI when the ViewModel is created
        checkSharedFolderUri()
    }

    /**
     * Checks the stored URI in SharedPreferences and updates the LiveData.
     * This can be called from onResume to detect changes made in other activities.
     */
    fun checkSharedFolderUri() {
        val prefs = getApplication<Application>().getSharedPreferences(Constants.SHARED_PREFS_NAME, Context.MODE_PRIVATE)
        val folderUriString = prefs.getString(Constants.EXTRA_FOLDER_URI, null)
        val currentUri = folderUriString?.toUri()

        // Only update if the value is different to avoid unnecessary reloads
        if (_selectedFolderUri.value != currentUri) {
            _selectedFolderUri.value = currentUri
            currentUri?.let { loadFiles(it) } ?: _files.postValue(emptyList()) // Clear files if URI is null
        } else if (currentUri != null && _files.value.isNullOrEmpty()) {
            // If URI is the same but file list is empty, try loading again
            loadFiles(currentUri)
        }
    }

    /**
     * Loads the list of files from a given folder URI.
     * This replaces the logic that was previously in MainActivity.
     */
    fun loadFiles(folderUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            // This is where you would implement your file listing logic.
            // Based on your original code, it seems you have a FileUtils class for this.
            val fileList = mutableListOf<FileItem>()
            val parentDocument = DocumentFile.fromTreeUri(getApplication(), folderUri)
            parentDocument?.listFiles()
                ?.forEach { docFile ->
                fileList.add(
                    FileItem(
                        name = docFile.name ?: "Unknown",
                        size = docFile.length(),
                        lastModified = docFile.lastModified(),
                        uri = docFile.uri
                    )
                )
            }
            withContext(Dispatchers.Main) {
                _files.value = fileList
            }
        }
    }

    /**
     * Handles the "paste" action from the menu.
     */
    fun pasteFromClipboard() {
        val folderUri = _selectedFolderUri.value
        if (folderUri == null) {
            Toast.makeText(getApplication(), R.string.shared_folder_not_selected, Toast.LENGTH_SHORT).show()
            return
        }

        val clipboard = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (clipboard.hasPrimaryClip() && clipboard.primaryClipDescription?.hasMimeType("text/plain") == true) {
            val textToPaste = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
            if (!textToPaste.isNullOrEmpty()) {
                viewModelScope.launch {
                val file = FileUtils.createTextFileInDir(getApplication(), folderUri, "paste", "txt", textToPaste)
                if (file != null && file.exists()) {
                    Toast.makeText(getApplication(), getApplication<Application>().getString(R.string.text_pasted_to_file, file.name), Toast.LENGTH_SHORT).show()
                    loadFiles(folderUri) // Refresh file list
                } else {
                    Toast.makeText(getApplication(), R.string.failed_to_paste_text, Toast.LENGTH_SHORT).show()
                }}
            } else {
                Toast.makeText(getApplication(), R.string.clipboard_empty, Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(getApplication(), R.string.no_text_in_clipboard, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Handles deleting a list of selected files.
     */
    fun deleteFiles(filesToDelete: List<FileItem>) {
        val folderUri = _selectedFolderUri.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            filesToDelete.forEach { fileItem ->
                // Use DocumentFile to delete the file via its URI
                DocumentFile.fromSingleUri(getApplication(), fileItem.uri)?.delete()
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    getApplication(),
                    getApplication<Application>().getString(R.string.files_deleted_successfully, filesToDelete.size),
                    Toast.LENGTH_SHORT
                ).show()
                loadFiles(folderUri) // Refresh file list
            }
        }
    }
}
