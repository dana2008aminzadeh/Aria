package ir.aria.downloader.engine

import java.io.File

/**
 * The surface [LinkIngestor] and the download service need from the extraction engine.
 * Having it as an interface keeps the queue logic testable on a plain JVM (no Python runtime).
 */
interface ExtractorApi {

    suspend fun probe(url: String): ExtractorEngine.Probe

    suspend fun resolve(url: String, preset: String): ExtractorEngine.Plan

    /** Exact-selection overload; legacy test/fake engines can keep using the preset path. */
    suspend fun resolve(
        url: String,
        preset: String,
        selection: ExtractorEngine.FormatSelection?
    ): ExtractorEngine.Plan = resolve(url, preset)

    suspend fun search(query: String, limit: Int = 1): ExtractorEngine.SearchResponse

    suspend fun download(
        url: String,
        outDir: File,
        formatId: String?,
        preset: String,
        shouldCancel: () -> Boolean,
        onProgress: (ExtractorEngine.Progress) -> Unit
    ): ExtractorEngine.Outcome

    suspend fun info(): ExtractorEngine.EngineInfo

    fun hasCookies(): Boolean
}
