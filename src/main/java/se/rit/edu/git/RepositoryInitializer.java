package se.rit.edu.git;

import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import se.rit.edu.util.ElapsedTimer;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class RepositoryInitializer {

    private static final String ORIGIN = "origin";

    private String repoDir;
    private String gitURI;
    private Git repoRef;

    public RepositoryInitializer(String uri, String baseName) {
        this.repoDir = "repos/" + baseName + "/master";
        this.gitURI = uri;
        File newGitRepo = new File(this.repoDir);
        if( newGitRepo.exists() ) {
            try {
                FileUtils.deleteDirectory(newGitRepo);
            } catch (IOException e) {
                System.err.println("Error deleting git repo");
            }
        }
        newGitRepo.mkdirs();
        try {
            ElapsedTimer timer = new ElapsedTimer();
            timer.start();
            this.repoRef = Git.cloneRepository()
                    .setURI(uri)
                    .setDirectory(newGitRepo)
                    .setCloneAllBranches(false)
                    .call();
            StoredConfig config = this.repoRef.getRepository().getConfig();
            config.setString("remote", ORIGIN, "url", uri);
            config.save();
            timer.end();
            System.out.println(String.format("Finished cloning: %s in %6dms",
                    GitUtil.getRepoNameFromGitURI(gitURI), timer.readMS()));
        } catch (GitAPIException e) {
            System.err.println("Git API error in se.rit.edu.git init.");
        } catch (IOException e) {
            System.err.println("IOException when setting remote in gew repo.");
        }
    }


    public List<RepositoryCommitReference> getComparableRepositories(int eachN) {
        return getComparableRepositories(null, eachN);
    }

    public List<RepositoryCommitReference> getComparableRepositories(String startCommit, int eachN) {
        try {
            ElapsedTimer timer = new ElapsedTimer();
            timer.start();
            // Get remote reference
            Collection<Ref> remoteRefs = this.repoRef.lsRemote()
                    .setRemote(ORIGIN)
                    .setTags(true)
                    .setHeads(false)
                    .call();
            final RevWalk revWalk = new RevWalk(this.repoRef.getRepository());

            // Get startCommitDate
            Date latestDate = startCommit != null ? revWalk.parseCommit(this.repoRef.getRepository().resolve(startCommit))
                    .getAuthorIdent()
                    .getWhen()
                    : new Date();

            // Map all valid commits to the date they were made
            final List<CommitData> commitData = remoteRefs.stream().map(ref -> {
                try {
                    RevCommit commit =  revWalk.parseCommit(ref.getObjectId());
                    String[] refNameSplit = ref.getName().split("/");
                    return new CommitData(commit.getName(), commit.getAuthorIdent().getWhen(),
                            refNameSplit[refNameSplit.length-1]);
                } catch (IOException e) {
                    System.err.println("Error when parsing git tags");
                    e.printStackTrace();
                    return null;
                }
            }).filter(commit -> commit != null && commit.getDate().before(latestDate))
            .sorted()
            .collect(Collectors.toList());
            timer.end();
            System.out.println(String.format("Finished gathering tags for %s in %6dms",
                    GitUtil.getRepoNameFromGitURI(this.gitURI), timer.readMS()));

            return IntStream.range(0, commitData.size())
                    .filter(n -> n % Math.min(eachN, commitData.size() - 1) == 0)
                    .mapToObj(commitData::get)
                    .map(commitDataObject -> new RepositoryCommitReference(
                            this.repoRef, GitUtil.getRepoNameFromGitURI(this.gitURI),
                            commitDataObject.getCommit(), commitDataObject.getTag()))
                    .collect(Collectors.toList());

        } catch (GitAPIException e) {
            System.err.println("Error when fetching tags");
        } catch (IOException e) {
            System.err.println("Error when fetching start commit date.");
        }
        return new ArrayList<>();
    }


    private class CommitData implements Comparable {
        private String commit;
        private Date date;
        private String tag;
        private CommitData(String commit, Date date, String tag) {
            this.commit = commit;
            this.date = date;
            this.tag = tag;
        }

        private String getCommit() {
            return this.commit;
        }
        private Date getDate() {
            return this.date;
        }
        private String getTag() {
            return this.tag;
        }

        @Override
        public int compareTo(Object o) {
            if( o instanceof CommitData) {
                if( o.equals(this) ) {
                    return 0;
                }
                if( ((CommitData) o).getDate().before(this.getDate()) ) {
                    return 1;
                }
                return -1;
            }
            return 0;
        }
    }
}
