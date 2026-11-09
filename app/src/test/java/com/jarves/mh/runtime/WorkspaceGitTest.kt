package com.jarves.mh.runtime

import com.jarves.mh.model.DiffLineType
import com.jarves.mh.model.GitFileStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceGitTest {
    @Test
    fun parsePorcelain_branchAndStatuses() {
        val result = WorkspaceGit.parsePorcelainV1(
            """
            ## main...origin/main [ahead 2]
             M app/src/Main.kt
            A  docs/new.md
             D gone.txt
            ?? untracked.txt
            UU conflict.kt
            R  old.txt -> new.txt
            """.trimIndent(),
        )
        assertEquals("main", result.branch)
        assertEquals(2, result.aheadCount)
        assertEquals(GitFileStatus.MODIFIED, result.entries.first { it.path == "app/src/Main.kt" }.status)
        assertEquals(GitFileStatus.ADDED, result.entries.first { it.path == "docs/new.md" }.status)
        assertEquals(GitFileStatus.DELETED, result.entries.first { it.path == "gone.txt" }.status)
        assertEquals(GitFileStatus.UNTRACKED, result.entries.first { it.path == "untracked.txt" }.status)
        assertEquals(GitFileStatus.CONFLICT, result.entries.first { it.path == "conflict.kt" }.status)
        val renamed = result.entries.first { it.path == "new.txt" }
        assertEquals(GitFileStatus.RENAMED, renamed.status)
        assertEquals("old.txt", renamed.renameFrom)
    }

    @Test
    fun parseUnifiedDiff_countsAndTypes() {
        val stats = WorkspaceGit.parseUnifiedDiff(
            """
            diff --git a/a.txt b/a.txt
            --- a/a.txt
            +++ b/a.txt
            @@ -1,2 +1,3 @@
             keep
            -old
            +new
            +extra
            """.trimIndent(),
        )
        assertEquals(2, stats.additions)
        assertEquals(1, stats.deletions)
        assertTrue(stats.lines.any { it.type == DiffLineType.ADDITION && it.text == "new" })
        assertTrue(stats.lines.any { it.type == DiffLineType.DELETION && it.text == "old" })
        assertTrue(stats.lines.any { it.type == DiffLineType.CONTEXT && it.text == "keep" })
    }

    @Test
    fun looksSecret_matchesCommonPaths() {
        assertTrue(WorkspaceGit.looksSecret(".env"))
        assertTrue(WorkspaceGit.looksSecret("app/secrets.xml"))
        assertTrue(WorkspaceGit.looksSecret("release.keystore"))
        assertTrue(WorkspaceGit.looksSecret("id_rsa"))
        assertFalse(WorkspaceGit.looksSecret("README.md"))
        assertFalse(WorkspaceGit.looksSecret("src/main/kotlin/App.kt"))
    }

    @Test
    fun remoteHelpers_detectHosts() {
        assertTrue(WorkspaceGit.isGitHubRemote("https://github.com/org/repo.git"))
        assertTrue(WorkspaceGit.isGitLabRemote("https://gitlab.com/org/repo.git", null))
        assertTrue(WorkspaceGit.isGitLabRemote("https://gitlab.example.com/a/b.git", "gitlab.example.com"))
        assertFalse(WorkspaceGit.isGitLabRemote("https://example.com/a/b.git", "gitlab.example.com"))
        assertFalse(WorkspaceGit.isGitLabRemote("https://notgitlab.com/org/repo.git", null))
        assertFalse(WorkspaceGit.isGitHubRemote("https://notgithub.com/org/repo.git"))
    }

    @Test
    fun stageCommand_neverUsesDashA() {
        val cmd = WorkspaceGit.stageCommand(listOf("a.kt", "b.kt"))
        assertEquals(listOf("git", "add", "--", "a.kt", "b.kt"), cmd)
        assertFalse(cmd.contains("-A"))
    }

    @Test
    fun parseLogAhead_readsHashAndSubject() {
        val commits = WorkspaceGit.parseLogAhead(
            "abcdef1234567890\tFix login\n" +
                "1234567890abcdef\tAdd screen\n",
        )
        assertEquals(2, commits.size)
        assertEquals("abcdef1234567890", commits[0].hash)
        assertEquals("Fix login", commits[0].subject)
    }

    @Test
    fun parseCommitPaths_skipsBlank() {
        val paths = WorkspaceGit.parseCommitPaths("README.md\n\napp/Main.kt\nREADME.md\n")
        assertEquals(listOf("README.md", "app/Main.kt"), paths)
    }

    @Test
    fun parseCommitNumstat_sumsInsertionsAndDeletions() {
        val stats = WorkspaceGit.parseCommitNumstat(
            """
            10	2	README.md
            3	0	app/Main.kt
            -	-	binary.bin
            """.trimIndent(),
        )
        assertEquals(13, stats.additions)
        assertEquals(2, stats.deletions)
        assertEquals(listOf("README.md", "app/Main.kt", "binary.bin"), stats.paths)
        assertEquals(10, stats.files.first { it.path == "README.md" }.additions)
        assertEquals(2, stats.files.first { it.path == "README.md" }.deletions)
        assertEquals(3, stats.files.first { it.path == "app/Main.kt" }.additions)
    }

    @Test
    fun planDiscard_addedGoesToClean_notCheckout() {
        val plan = WorkspaceGit.planDiscard(
            listOf(
                WorkspaceGit.DiscardInput("new.kt", GitFileStatus.ADDED),
                WorkspaceGit.DiscardInput("scratch.txt", GitFileStatus.UNTRACKED),
                WorkspaceGit.DiscardInput("edit.kt", GitFileStatus.MODIFIED),
            ),
        )
        assertEquals(listOf("edit.kt"), plan.checkoutPaths)
        assertEquals(listOf("new.kt", "scratch.txt"), plan.cleanPaths)
        assertFalse(plan.hasConflict)
    }

    @Test
    fun planDiscard_renamedRestoresBothPaths() {
        val plan = WorkspaceGit.planDiscard(
            listOf(
                WorkspaceGit.DiscardInput("new-name.kt", GitFileStatus.RENAMED, renameFrom = "old-name.kt"),
            ),
        )
        assertEquals(listOf("new-name.kt", "old-name.kt"), plan.checkoutPaths)
        assertTrue(plan.cleanPaths.isEmpty())
    }

    @Test
    fun planDiscard_conflictIsFlagged() {
        val plan = WorkspaceGit.planDiscard(
            listOf(WorkspaceGit.DiscardInput("merge.kt", GitFileStatus.CONFLICT)),
        )
        assertTrue(plan.hasConflict)
    }

    @Test
    fun commitCommand_includesOnlySelectedPaths() {
        val cmd = WorkspaceGit.commitCommand("/tmp/msg.txt", listOf("a.kt", "b.kt"))
        assertEquals(
            listOf("git", "commit", "-F", "/tmp/msg.txt", "--", "a.kt", "b.kt"),
            cmd,
        )
    }

    @Test
    fun headMatchesExpected_ignoresWhitespaceAndCase() {
        assertTrue(
            WorkspaceGit.headMatchesExpected(
                "ABCDEF1234567890",
                "  abcdef1234567890\n",
            ),
        )
        assertFalse(
            WorkspaceGit.headMatchesExpected(
                "aaaaaaaaaaaaaaaa",
                "bbbbbbbbbbbbbbbb",
            ),
        )
    }

    @Test
    fun discardAndUnstageCommands_usePathspecs() {
        assertEquals(
            listOf("git", "checkout", "HEAD", "--", "a.kt"),
            WorkspaceGit.discardTrackedCommand(listOf("a.kt")),
        )
        assertEquals(
            listOf("git", "clean", "-f", "--", "b.kt"),
            WorkspaceGit.discardUntrackedCommand(listOf("b.kt")),
        )
        assertEquals(
            listOf("git", "reset", "HEAD", "--", "c.kt"),
            WorkspaceGit.unstageCommand(listOf("c.kt")),
        )
    }
}
