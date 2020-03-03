package se.rit.edu.satd.mining.commit;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.treewalk.TreeWalk;
import se.rit.edu.git.GitUtil;
import se.rit.edu.git.RepositoryCommitReference;
import se.rit.edu.git.commitlocator.CommitLocatorUtil;
import se.rit.edu.satd.SATDDifference;
import se.rit.edu.satd.SATDInstance;
import se.rit.edu.util.GroupedComment;
import se.rit.edu.util.JavaParseUtil;
import se.rit.edu.util.NullGroupedComment;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class CommitToCommitDiff {

    private RepositoryCommitReference oldRepo;
    private RepositoryCommitReference newRepo;
    private Git gitInstance;

    private RevCommit oldCommit;
    private RevCommit newCommit;

    private DiffFormatter diffFormatter;

    private List<DiffEntry> diffEntries;

    // Store the comments found in each file in the new commit as they are parsed to
    // avoid parsing the same java file multiple times
    private Map<String, List<GroupedComment>> parsedCommentsInNewCommit = new HashMap<>();

    public CommitToCommitDiff(RepositoryCommitReference oldRepo, RepositoryCommitReference newRepo) {
        this.oldRepo = oldRepo;
        this.newRepo = newRepo;
        this.gitInstance = this.oldRepo.getGitInstance();
        this.oldCommit = oldRepo.getCommit();
        this.newCommit = newRepo.getCommit();
        this.diffEntries = GitUtil.getDiffEntries(this.gitInstance, this.oldCommit.getTree(),
                this.newCommit.getTree());
        this.diffFormatter = this.getFormatter(this.gitInstance);
    }

    public void loadDiffsFor(SATDDifference diff, String filePath, GroupedComment comment) {
        diffEntries.stream()
                .filter(entry -> entry.getOldPath().equals(filePath))
                .forEach(diffEntry -> this.placeDiffs(diff, diffEntry, comment));

    }

    private void placeDiffs(SATDDifference diff, DiffEntry diffEntry, GroupedComment comment) {
        final List<SATDInstance> satd = new ArrayList<>();

        // TODO add file
        // TODO add SATD Added Case
        switch (diffEntry.getChangeType()) {
            case RENAME:
                satd.add(
                        new SATDInstance(
                            diffEntry.getOldPath(),
                            diffEntry.getNewPath(),
                            comment,
                            comment,
                            SATDInstance.SATDResolution.FILE_PATH_CHANGED
                ));
                diff.addFileRenamedSATD(satd);
                break;
            case DELETE:
                satd.add(
                        new SATDInstance(
                                diffEntry.getOldPath(),
                                diffEntry.getNewPath(),
                                comment,
                                new NullGroupedComment(),
                                SATDInstance.SATDResolution.FILE_REMOVED
                        )
                );
                diff.addFileRemovedSATD(satd);
                break;
            case MODIFY:
                try {
                    // Find any SATD that might be present in the new section
                    final List<Edit> editsToSATDComment = this.diffFormatter.toFileHeader(diffEntry).toEditList().stream()
                            .filter(edit -> editOccursBetweenLines(edit, comment.getStartLine(), comment.getEndLine()))
                            .collect(Collectors.toList());
                    final List<GroupedComment> updatedComments = editsToSATDComment.stream()
                            .flatMap( edit -> this.getCommentsInFileInNewRepository(diffEntry.getNewPath()).stream()
                                    .filter( c -> editOccursBetweenLines(edit, c.getStartLine(), c.getEndLine())))
                            .collect(Collectors.toList());
                    if( updatedComments.isEmpty() && !editsToSATDComment.isEmpty() ) {
                        satd.add(
                                new SATDInstance(
                                        diffEntry.getOldPath(),
                                        diffEntry.getNewPath(),
                                        comment,
                                        new NullGroupedComment(),
                                        SATDInstance.SATDResolution.SATD_REMOVED
                                )
                        );
                        diff.addAddressedOrChangedSATD(satd);
                    } else if( !updatedComments.isEmpty() ){
                        // TODO Determine if the SATD was changed or removed
                        diff.addAddressedOrChangedSATD(
                                updatedComments.stream().map(newComment -> new SATDInstance(
                                        diffEntry.getOldPath(),
                                        diffEntry.getNewPath(),
                                        comment,
                                        newComment,
                                        SATDInstance.SATDResolution.SATD_POSSIBLY_REMOVED))
                                .collect(Collectors.toList())
                        );
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                break;
            case ADD:
                satd.add(
                        new SATDInstance(
                                diffEntry.getOldPath(),
                                diffEntry.getNewPath(),
                                new NullGroupedComment(),
                                comment, // TODO is this right?
                                SATDInstance.SATDResolution.SATD_ADDED
                        )
                );
                break;
        }
    }

    private DiffFormatter getFormatter(Git gitInstance) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        DiffFormatter formatter = new DiffFormatter(outputStream);
        formatter.setRepository(gitInstance.getRepository());
        formatter.setContext(0);
        return formatter;
    }

    private List<GroupedComment> getCommentsInFileInNewRepository(String fileName) {
        if( this.parsedCommentsInNewCommit.containsKey(fileName) ) {
            // Return the value if already parsed
            return this.parsedCommentsInNewCommit.get(fileName);
        }
        // Otherwise, parse and then store the value
        try {
            List<GroupedComment> commentsInFile = JavaParseUtil.parseFileForComments(this.getFileContents(fileName));
            this.parsedCommentsInNewCommit.put(fileName, commentsInFile);
            return commentsInFile;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

    }

    private InputStream getFileContents(String fileName) throws IOException {
        final TreeWalk treeWalk = GitUtil.getTreeWalker(this.gitInstance, this.newCommit);
        while (treeWalk.next()) {
            if( treeWalk.getPathString().equals(fileName) ) {
                return this.gitInstance.getRepository().open(treeWalk.getObjectId(0)).openStream();
            }
        }
        System.err.println("File not found -- this SHOULD NOT happen!");
        // Return a blank stream if the file was not found
        return new InputStream() {
            @Override
            public int read() {
                return -1;  // end of stream
            }
        };
    }

    // TODO make this a util function as it is duplicate code
    private static boolean editOccursBetweenLines(Edit edit, int startLine, int endLine) {
        return
                // Starts before the start and ends after the start
                (edit.getBeginA() <= startLine && edit.getEndA() >= startLine ) ||
                        // Starts before the end, and ends after the end
                        (edit.getBeginA() <= endLine && edit.getEndA() >= endLine) ||
                        // Starts after the start and ends before the end
                        (edit.getBeginA() >= startLine && edit.getEndA() <= endLine);
    }
}
