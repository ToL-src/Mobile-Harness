package com.jarves.mh.runtime

import com.jarves.mh.model.DiffLine
import com.jarves.mh.model.DiffLineType
import com.jarves.mh.model.GitFileStatus

/** Pure git porcelain/diff helpers. Process execution stays in the ViewModel. */
object WorkspaceGit {
    data class PorcelainResult(
        val branch: String?,
        val aheadCount: Int = 0,
        val entries: List<PorcelainEntry>,
    )

    data class PorcelainEntry(
        val path: String,
        val status: GitFileStatus,
        val renameFrom: String? = null,
    )

    data class DiffStats(
        val lines: List<DiffLine>,
        val additions: Int,
        val deletions: Int,
    )

    fun statusCommand(): List<String> =
        listOf("git", "status", "--porcelain=v1", "-b", "--untracked-files=all")

    fun diffHeadCommand(path: String): List<String> =
        listOf("git", "diff", "HEAD", "--", path)

    fun diffUntrackedCommand(path: String): List<String> =
        listOf("git", "diff", "--no-index", "--", "/dev/null", path)

    fun stageCommand(paths: List<String>): List<String> =
        listOf("git", "add", "--") + paths

    fun commitCommand(messageFileGuestPath: String, paths: List<String> = emptyList()): List<String> =
        buildList {
            addAll(listOf("git", "commit", "-F", messageFileGuestPath))
            if (paths.isNotEmpty()) {
                add("--")
                addAll(paths)
            }
        }

    fun pushCommand(): List<String> = listOf("git", "push")

    /** Commits on HEAD not in upstream. Empty upstream → empty output / non-zero handled by caller. */
    fun logAheadCommand(): List<String> =
        listOf("git", "log", "--format=%H%x09%s", "@{upstream}..HEAD")

    fun showCommitFilesCommand(hash: String): List<String> =
        listOf("git", "show", "--pretty=format:", "--name-only", hash)

    fun showCommitNumstatCommand(hash: String): List<String> =
        listOf("git", "show", "--pretty=format:", "--numstat", hash)

    data class NumstatFile(val path: String, val additions: Int, val deletions: Int)

    data class NumstatTotals(
        val files: List<NumstatFile>,
        val additions: Int,
        val deletions: Int,
    ) {
        val paths: List<String> get() = files.map { it.path }
    }

    fun parseCommitNumstat(stdout: String): NumstatTotals {
        var additions = 0
        var deletions = 0
        val files = ArrayList<NumstatFile>()
        stdout.lineSequence().forEach { raw ->
            val line = raw.trimEnd('\r').trim()
            if (line.isBlank()) return@forEach
            val parts = line.split('\t')
            if (parts.size < 3) return@forEach
            val add = parts[0].toIntOrNull() ?: 0 // binary files use "-"
            val del = parts[1].toIntOrNull() ?: 0
            additions += add
            deletions += del
            files += NumstatFile(path = parts[2], additions = add, deletions = del)
        }
        return NumstatTotals(files = files.distinctBy { it.path }, additions = additions, deletions = deletions)
    }

    fun softResetLastCommand(): List<String> =
        listOf("git", "reset", "--soft", "HEAD~1")

    fun revParseHeadCommand(): List<String> = listOf("git", "rev-parse", "HEAD")

    data class LogCommit(val hash: String, val subject: String)

    fun parseLogAhead(stdout: String): List<LogCommit> =
        stdout.lineSequence()
            .map { it.trimEnd('\r') }
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val tab = line.indexOf('\t')
                if (tab <= 0) return@mapNotNull null
                val hash = line.substring(0, tab).trim()
                val subject = line.substring(tab + 1).trim()
                if (hash.isBlank()) null else LogCommit(hash = hash, subject = subject)
            }
            .toList()

    fun parseCommitPaths(stdout: String): List<String> =
        stdout.lineSequence()
            .map { it.trimEnd('\r').trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()

    fun discardTrackedCommand(paths: List<String>): List<String> =
        listOf("git", "checkout", "HEAD", "--") + paths

    fun discardUntrackedCommand(paths: List<String>): List<String> =
        listOf("git", "clean", "-f", "--") + paths

    data class DiscardInput(
        val path: String,
        val status: GitFileStatus,
        val renameFrom: String? = null,
    )

    data class DiscardPlan(
        val checkoutPaths: List<String>,
        val cleanPaths: List<String>,
        val hasConflict: Boolean,
    )

    /** Pure planning for discard — ViewModel only executes the resulting commands. */
    fun planDiscard(files: List<DiscardInput>): DiscardPlan {
        val checkout = linkedSetOf<String>()
        val clean = linkedSetOf<String>()
        var conflict = false
        files.forEach { file ->
            when (file.status) {
                GitFileStatus.UNTRACKED -> clean += file.path
                GitFileStatus.ADDED -> clean += file.path
                GitFileStatus.RENAMED -> {
                    checkout += file.path
                    file.renameFrom?.let { checkout += it }
                }
                GitFileStatus.CONFLICT -> conflict = true
                GitFileStatus.MODIFIED, GitFileStatus.DELETED -> checkout += file.path
            }
        }
        return DiscardPlan(
            checkoutPaths = checkout.toList(),
            cleanPaths = clean.toList(),
            hasConflict = conflict,
        )
    }

    fun headMatchesExpected(expectedHash: String, actualHead: String): Boolean =
        actualHead.trim().equals(expectedHash.trim(), ignoreCase = true)

    fun identityNameCommand(): List<String> = listOf("git", "config", "--get", "user.name")

    fun identityEmailCommand(): List<String> = listOf("git", "config", "--get", "user.email")

    fun setIdentityNameCommand(name: String): List<String> =
        listOf("git", "config", "--local", "user.name", name)

    fun setIdentityEmailCommand(email: String): List<String> =
        listOf("git", "config", "--local", "user.email", email)

    fun remoteUrlCommand(): List<String> = listOf("git", "remote", "get-url", "origin")

    fun unstageCommand(paths: List<String>): List<String> =
        listOf("git", "reset", "HEAD", "--") + paths

    fun parsePorcelainV1(stdout: String): PorcelainResult {
        var branch: String? = null
        var aheadCount = 0
        val entries = ArrayList<PorcelainEntry>()
        stdout.lineSequence().forEach { raw ->
            val line = raw.trimEnd('\r')
            if (line.isEmpty()) return@forEach
            if (line.startsWith("## ")) {
                val header = parseBranchHeader(line.removePrefix("## ").trim())
                branch = header.branch
                aheadCount = header.aheadCount
                return@forEach
            }
            if (line.length < 3) return@forEach
            val xy = line.substring(0, 2)
            val rest = line.substring(2).trimStart(' ')
            val (path, renameFrom) = parsePathField(rest)
            if (path.isBlank()) return@forEach
            entries += PorcelainEntry(path = path, status = statusFromXy(xy), renameFrom = renameFrom)
        }
        return PorcelainResult(branch = branch, aheadCount = aheadCount, entries = entries.distinctBy { it.path })
    }

    fun parseUnifiedDiff(diff: String): DiffStats {
        var additions = 0
        var deletions = 0
        val lines = ArrayList<DiffLine>()
        var oldLine: Int? = null
        var newLine: Int? = null
        diff.lineSequence().forEach { raw ->
            val line = raw.trimEnd('\r')
            when {
                line.startsWith("diff ") || line.startsWith("index ") ||
                    line.startsWith("--- ") || line.startsWith("+++ ") -> {
                    lines += DiffLine(DiffLineType.INFO, line)
                }
                line.startsWith("@@") -> {
                    val match = HUNK_HEADER.find(line)
                    if (match != null) {
                        oldLine = match.groupValues[1].toIntOrNull()
                        newLine = match.groupValues[2].toIntOrNull()
                    }
                    lines += DiffLine(DiffLineType.INFO, line)
                }
                line.startsWith("+") -> {
                    lines += DiffLine(DiffLineType.ADDITION, line.removePrefix("+"), oldLine = null, newLine = newLine)
                    newLine = newLine?.plus(1)
                    additions++
                }
                line.startsWith("-") -> {
                    lines += DiffLine(DiffLineType.DELETION, line.removePrefix("-"), oldLine = oldLine, newLine = null)
                    oldLine = oldLine?.plus(1)
                    deletions++
                }
                line.startsWith("\\") -> lines += DiffLine(DiffLineType.INFO, line)
                else -> {
                    val text = if (line.startsWith(" ")) line.drop(1) else line
                    lines += DiffLine(DiffLineType.CONTEXT, text, oldLine = oldLine, newLine = newLine)
                    oldLine = oldLine?.plus(1)
                    newLine = newLine?.plus(1)
                }
            }
        }
        return DiffStats(lines = lines, additions = additions, deletions = deletions)
    }

    fun looksSecret(path: String): Boolean {
        val name = path.substringAfterLast('/').lowercase()
        return SECRET_EXACT.contains(name) ||
            name.startsWith(".env") ||
            name.endsWith(".pem") ||
            name.endsWith(".keystore") ||
            name.endsWith(".jks") ||
            name.contains("credential") ||
            name.contains("secret") ||
            name.startsWith("id_rsa") ||
            name.startsWith("id_ed25519")
    }

    fun isGitHubRemote(url: String): Boolean =
        remoteHost(url)?.equals("github.com", ignoreCase = true) == true

    fun isGitLabRemote(url: String, knownHost: String?): Boolean {
        val host = remoteHost(url) ?: return false
        if (host.equals("gitlab.com", ignoreCase = true)) return true
        val known = knownHost?.substringBefore('/')?.trim()?.lowercase().orEmpty()
        return known.isNotBlank() && host.equals(known, ignoreCase = true)
    }

    private fun remoteHost(url: String): String? {
        val trimmed = url.trim()
        return runCatching {
            when {
                trimmed.startsWith("git@") ->
                    trimmed.removePrefix("git@").substringBefore(':').substringBefore('/')
                else -> {
                    val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
                    java.net.URI(withScheme).host
                }
            }?.lowercase()?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private data class BranchHeader(val branch: String, val aheadCount: Int)

    private fun parseBranchHeader(header: String): BranchHeader {
        val head = header.substringBefore("...")
        val branch = head.removePrefix("heads/").trim().ifBlank { header.trim() }
        val ahead = AHEAD_COUNT.find(header)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        return BranchHeader(branch = branch, aheadCount = ahead)
    }

    private fun parsePathField(rest: String): Pair<String, String?> {
        if (" -> " in rest) {
            val from = unquote(rest.substringBefore(" -> ").trim())
            val to = unquote(rest.substringAfter(" -> ").trim())
            return to to from
        }
        return unquote(rest.trim()) to null
    }

    private fun unquote(value: String): String {
        if (value.length >= 2 && value.startsWith('"') && value.endsWith('"')) {
            return value.substring(1, value.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
        }
        return value
    }

    private fun statusFromXy(xy: String): GitFileStatus {
        val x = xy.getOrElse(0) { ' ' }
        val y = xy.getOrElse(1) { ' ' }
        return when {
            x == 'U' || y == 'U' || (x == 'A' && y == 'A') || (x == 'D' && y == 'D') -> GitFileStatus.CONFLICT
            x == '?' || y == '?' -> GitFileStatus.UNTRACKED
            x == 'R' || y == 'R' || x == 'C' || y == 'C' -> GitFileStatus.RENAMED
            x == 'A' || y == 'A' -> GitFileStatus.ADDED
            x == 'D' || y == 'D' -> GitFileStatus.DELETED
            else -> GitFileStatus.MODIFIED
        }
    }

    private val HUNK_HEADER = Regex("""^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@""")
    private val AHEAD_COUNT = Regex("""\[ahead (\d+)""")

    private val SECRET_EXACT = setOf(
        ".env",
        ".env.local",
        ".env.production",
        "credentials.json",
        "credentials.xml",
        "secrets.xml",
        "google-services.json",
    )
}
