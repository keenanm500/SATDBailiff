package se.rit.edu.git.commitlocator;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.errors.CorruptObjectException;
import org.eclipse.jgit.revwalk.RevCommit;
import se.rit.edu.git.GitUtil;
import se.rit.edu.git.models.CommitMetaData;
import se.rit.edu.git.models.NullCommitMetaData;
import se.rit.edu.satd.SATDInstance;

import java.io.IOException;
import java.util.List;

public class FileRemovedOrRenamedCommitLocator extends CommitLocator {

    /**
     *
     * @param gitInstance gitInstance
     * @param satdInstance SATD Instance object
     * @param v1 first commit bound for finding SATD
     * @param v2 second commit bound for finding SATD
     *
     * Code partially copied from https://stackoverflow.com/questions/17296278/jgit-detect-rename-in-working-copy
     * TODO Check if the SATD in the removed file was moved to a different file
     */
    @Override
    public void findCommitAddressed(Git gitInstance, SATDInstance satdInstance, String v1, String v2) {
        try {
            List<RevCommit> commitsBetween = CommitLocatorUtil.getCommitsBetween(gitInstance,
                    gitInstance.getRepository().resolve(v1), gitInstance.getRepository().resolve(v2));
            String fileToFindDeletion = satdInstance.getOldFile();
            RevCommit commitIfRenamed = null;
            for( int i = 1; i < commitsBetween.size(); i++ ) {
                List<DiffEntry> lde = GitUtil.getDiffEntries(gitInstance,
                        commitsBetween.get(i-1).getTree(), commitsBetween.get(i).getTree());
                for (DiffEntry de : lde) {
                    // If the file was removed and is the file we're looking for,
                    // Then return the commit which removed the file
                    if (de.getChangeType().equals(DiffEntry.ChangeType.DELETE) &&
                        de.getOldPath().equals(fileToFindDeletion)) {
                        satdInstance.setNameOfFileWhenAddressed(fileToFindDeletion);
                        satdInstance.setResolution(SATDInstance.SATDResolution.FILE_REMOVED);
                        satdInstance.setCommitAddressed(new CommitMetaData(commitsBetween.get(i)));
                        return;
                    }
                    // If the file was renamed, and is the file we're looking for,
                    // Update the name so we can find when the renamed file was removed
                    // Keep note of the last commit that renamed the file, to be returned
                    // if we do not find that the file was removed
                    if( de.getChangeType().equals(DiffEntry.ChangeType.RENAME) &&
                        de.getOldPath().equals(fileToFindDeletion)) {
                        satdInstance.setResolution(SATDInstance.SATDResolution.FILE_PATH_CHANGED);
                        // Name gets set here in the case of rename being the only operation
                        satdInstance.setNameOfFileWhenAddressed(fileToFindDeletion);
                        fileToFindDeletion = de.getNewPath();
                        commitIfRenamed = commitsBetween.get(i);
                    }
                }
            }
            if( commitIfRenamed != null ) {
                satdInstance.setNewFile(fileToFindDeletion);
                satdInstance.setCommitAddressed(new CommitMetaData(commitIfRenamed));
            }
        } catch (CorruptObjectException e) {
            System.err.println("Corrupted tree when parsing files in repository.");
            satdInstance.setResolution(SATDInstance.SATDResolution.ERROR_UNKNOWN);
            satdInstance.setCommitAddressed(new NullCommitMetaData());
        } catch (IOException e) {
            System.err.println("Error when diffing files");
            satdInstance.setResolution(SATDInstance.SATDResolution.ERROR_UNKNOWN);
            satdInstance.setCommitAddressed(new NullCommitMetaData());
        }
    }
}
