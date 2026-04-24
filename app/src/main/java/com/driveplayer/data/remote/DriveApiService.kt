package com.driveplayer.data.remote

import com.driveplayer.data.model.DriveFileListResponse
import retrofit2.http.GET
import retrofit2.http.Query

interface DriveApiService {

    /**
     * Lists files in a Drive folder.
     * Query examples:
     *   Folder root:  "'root' in parents and trashed=false"
     *   Folder X:     "'FOLDER_ID' in parents and trashed=false"
     * We additionally filter server-side to only folders + video types for efficiency.
     */
    @GET("drive/v3/files")
    suspend fun listFiles(
        @Query("q")         query: String,
        @Query("fields")    fields: String    = "files(id,name,mimeType,size,modifiedTime,thumbnailLink),nextPageToken",
        @Query("pageSize")  pageSize: Int     = 100,
        @Query("orderBy")   orderBy: String   = "folder,name",
        @Query("pageToken") pageToken: String? = null,
    ): DriveFileListResponse
}
