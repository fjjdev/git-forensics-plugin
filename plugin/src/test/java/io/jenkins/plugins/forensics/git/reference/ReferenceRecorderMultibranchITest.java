package io.jenkins.plugins.forensics.git.reference;

import java.util.Objects;

import org.junit.ClassRule;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.JenkinsRule;

import com.cloudbees.hudson.plugins.folder.computed.FolderComputation;

import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;
import hudson.model.Run;
import hudson.model.queue.QueueTaskFuture;
import jenkins.branch.BranchProperty;
import jenkins.branch.BranchSource;
import jenkins.branch.DefaultBranchPropertyStrategy;
import jenkins.plugins.git.GitBranchSCMHead;
import jenkins.plugins.git.GitSCMSource;
import jenkins.plugins.git.GitSampleRepoRule;
import jenkins.scm.api.SCMHead;
import jenkins.scm.api.SCMSource;

import static io.jenkins.plugins.forensics.git.assertions.Assertions.*;

/**
 * Integrationtest for finding the correct reference point for multibranch pipelines.
 *
 * @author Arne Schöntag also see https://github.com/jenkinsci/workflow-multibranch-plugin
 */
@SuppressWarnings("PMD.SignatureDeclareThrowsException")
public class ReferenceRecorderMultibranchITest {
    private static final String JENKINS_FILE = "Jenkinsfile";
    private static final String SOURCE_FILE = "file";
    private static final String FEATURE = "feature";
    private static final String MASTER = "master";
    private static final String ADDITIONAL_SOURCE_FILE = "test.txt";

    @ClassRule
    public static BuildWatcher buildWatcher = new BuildWatcher();
    @Rule
    public JenkinsRule r = new JenkinsRule();
    @Rule
    public GitSampleRepoRule sampleRepo = new GitSampleRepoRule();

    /**
     * Builds a  multibranch-pipeline with a master and a feature branch, builds them and checks if the correct
     * reference build is found.
     *
     * @throws Exception
     *         in case of an unexpected error during the tests
     * @see <a href="https://github.com/jenkinsci/workflow-multibranch-plugin/blob/master/src/test/java/org/jenkinsci/plugins/workflow/multibranch/WorkflowMultiBranchProjectTest.java">Integration
     *         tests for Pipelines</a>
     */
    @Test
    public void shouldFindCorrectBuildForMultibranchPipeline() throws Exception {
        initializeGit();

        WorkflowMultiBranchProject mp = createMultiBranchPipeline();
        WorkflowRun masterBuild = buildMaster(mp, 1);
        verifyRecordSize(masterBuild, 2);

        createFeatureBranchAndAddCommits();

        WorkflowRun featureBuild = buildFeature(mp, 1);

        GitCommitsRecord featureRecord = featureBuild.getAction(GitCommitsRecord.class);
        assertThat(featureRecord).isNotNull();
        assertThat(featureRecord.getCommits()).hasSize(3);

        GitBranchMasterIntersectionFinder finder = featureBuild.getAction(GitBranchMasterIntersectionFinder.class);
        assertThat(finder).isNotNull();
        assertThat("p/master#1").isEqualTo(finder.getBuildId());
    }

    private WorkflowRun buildFeature(final WorkflowMultiBranchProject mp, final int buildNumber) throws Exception {
        WorkflowRun featureBuild = buildBranch(mp, FEATURE);

        assertThat(featureBuild.getNumber()).isEqualTo(buildNumber);
        r.assertLogContains("SUBSEQUENT CONTENT", featureBuild);
        r.assertLogContains("branch=feature", featureBuild);

        return featureBuild;
    }

    private void initializeGit(final String... parameters) throws Exception {
        initializeGitRepository(String.format("echo \"branch=${env.BRANCH_NAME}\"; "
                + "node {checkout scm; echo readFile('file'); "
                + "echo \"GitForensics\"; "
                + "gitForensics(%s)}", String.join(",", parameters)));
    }

    private WorkflowRun buildBranch(final WorkflowMultiBranchProject mp, final String feature) throws Exception {
        WorkflowJob p = scheduleAndFindBranchProject(mp, feature);
        r.waitUntilNoActivity();

        return p.getLastBuild();
    }

    private void createFeatureBranchAndAddCommits() throws Exception {
        // Checkout a new feature branch and add a new commit
        sampleRepo.git("checkout", "-b", FEATURE);
        sampleRepo.write(JENKINS_FILE,
                "echo \"branch=${env.BRANCH_NAME}\"; node {checkout scm; echo readFile('file').toUpperCase(); echo \"GitForensics\"; gitForensics()}");
        sampleRepo.write(SOURCE_FILE, "subsequent content");
        sampleRepo.git("commit", "--all", "--message=tweaked");
    }

    private void initializeGitRepository(final String jenkinsFileContent) throws Exception {
        sampleRepo.init();
        sampleRepo.write(JENKINS_FILE, jenkinsFileContent);
        sampleRepo.write(SOURCE_FILE, "master content");
        sampleRepo.git("add", JENKINS_FILE);
        sampleRepo.git("commit", "--all", "--message=flow");
    }

    private WorkflowMultiBranchProject createMultiBranchPipeline() throws java.io.IOException {
        WorkflowMultiBranchProject mp = r.jenkins.createProject(WorkflowMultiBranchProject.class, "p");
        mp.getSourcesList().add(
                new BranchSource(new GitSCMSource(null, sampleRepo.toString(), "", "*",
                        "", false),
                        new DefaultBranchPropertyStrategy(new BranchProperty[0])));
        for (SCMSource source : mp.getSCMSources()) {
            assertThat(mp).isEqualTo(source.getOwner());
        }
        return mp;
    }

    /**
     * Checks if the intersectionpoint is found if the master is ahead of the feature branch.
     */
    @Test
    public void shouldFindCorrectBuildForMultibranchPipelineWithExtraCommitsAfterBranchPointOnMaster()
            throws Exception {
        initializeGit();

        WorkflowMultiBranchProject mp = createMultiBranchPipeline();
        WorkflowRun masterBuild = buildMaster(mp, 1);
        verifyRecordSize(masterBuild, 2);

        createFeatureBranchAndAddCommits();

        // Add some new master commits
        sampleRepo.git("checkout", MASTER);
        sampleRepo.write(ADDITIONAL_SOURCE_FILE, "test");
        sampleRepo.git("add", ADDITIONAL_SOURCE_FILE);
        sampleRepo.git("commit", "--all", "--message=test");

        WorkflowRun nextMaster = buildMaster(mp, 2);
        verifyRecordSize(nextMaster, 1);
        WorkflowRun featureBuild = buildFeature(mp, 1);

        GitCommitsRecord featureRecord = featureBuild.getAction(GitCommitsRecord.class);
        assertThat(featureRecord).isNotNull();
        assertThat(featureRecord.getCommits()).hasSize(3);

        GitBranchMasterIntersectionFinder finder = featureBuild.getAction(GitBranchMasterIntersectionFinder.class);
        assertThat(finder).isNotNull();
        assertThat("p/master#1").isEqualTo(finder.getBuildId());
    }

    private WorkflowRun buildMaster(final WorkflowMultiBranchProject mp, final int buildNumber) throws Exception {
        WorkflowJob p = scheduleAndFindBranchProject(mp, MASTER);
        assertThat(new GitBranchSCMHead(MASTER)).isEqualTo(SCMHead.HeadByItem.findHead(p));
        r.waitUntilNoActivity();

        WorkflowRun build = p.getLastBuild();
        assertThat(build.getNumber()).isEqualTo(buildNumber);
        r.assertLogContains("master content", build);
        r.assertLogContains("branch=master", build);

        return build;
    }

    private void verifyRecordSize(final WorkflowRun build, final int size) {
        GitCommitsRecord masterRecord = build.getAction(GitCommitsRecord.class);

        assertThat(masterRecord).isNotNull().isNotEmpty().hasSize(size);
    }

    /**
     * Checks if the correct build is found even if the reference build has commits that the feature build does not.
     * This also checks the algorithm if the config skipUnknownCommits is disabled.
     */
    @Test
    public void shouldFindBuildWithMultipleCommitsInReferenceBuild() throws Exception {
        initializeGit("latestBuildIfNotFound: false");

        WorkflowMultiBranchProject mp = createMultiBranchPipeline();
        WorkflowRun masterBuild = buildMaster(mp, 1);
        verifyRecordSize(masterBuild, 2);

        sampleRepo.write(ADDITIONAL_SOURCE_FILE, "test");
        sampleRepo.git("add", ADDITIONAL_SOURCE_FILE);
        sampleRepo.git("commit", "--all", "--message=test");

        // The Test will automatically build this new branch.
        // For this scenario a new commit will be added later and build a second time.
        sampleRepo.git("checkout", "-b", FEATURE);

        // new master commits
        sampleRepo.git("checkout", MASTER);
        sampleRepo.write(ADDITIONAL_SOURCE_FILE, "test edit");
        sampleRepo.git("commit", "--all", "--message=edit-test");

        WorkflowRun nextMaster = buildMaster(mp, 2);
        verifyRecordSize(nextMaster, 2);

        // commits on feature branch
        sampleRepo.git("checkout", FEATURE);
        sampleRepo.write(JENKINS_FILE,
                "echo \"branch=${env.BRANCH_NAME}\"; node {checkout scm; echo readFile('file').toUpperCase(); echo \"GitForensics\"; gitForensics()}");
        sampleRepo.write(SOURCE_FILE, "subsequent content");
        sampleRepo.git("commit", "--all", "--message=tweaked");

        WorkflowRun featureBuild = buildFeature(mp, 2);

        GitCommitsRecord gitCommit = featureBuild.getAction(GitCommitsRecord.class);
        assertThat(gitCommit).isNotNull();
        // Only 1 Commit since the checkout is build as well
        assertThat(gitCommit.getCommits()).hasSize(1);

        GitBranchMasterIntersectionFinder finder = featureBuild.getAction(GitBranchMasterIntersectionFinder.class);
        assertThat(finder).isNotNull();
        assertThat(finder.getBuildId()).isEqualTo("p/master#2");
    }

    // Testing the configs

    /**
     * Check negative case if maxCommits is too low to find the reference Point Checks last 2 commits when 3 would be
     * needed.
     */
    @Test
    public void shouldNotFindBuildWithInsufficientMaxCommitsForMultibranchPipeline() throws Exception {
        initializeGit("maxCommits: 2");

        WorkflowMultiBranchProject mp = createMultiBranchPipeline();
        WorkflowRun masterBuild = buildMaster(mp, 1);
        verifyRecordSize(masterBuild, 2);

        sampleRepo.git("checkout", "-b", FEATURE);
        sampleRepo.write(JENKINS_FILE,
                "echo \"branch=${env.BRANCH_NAME}\"; node {checkout scm; echo readFile('file').toUpperCase(); echo \"GitForensics\"; gitForensics maxCommits: 2}");
        sampleRepo.write(SOURCE_FILE, "subsequent content");
        sampleRepo.git("commit", "--all", "--message=tweaked");
        // Second Commit
        sampleRepo.write(ADDITIONAL_SOURCE_FILE, "Test");
        sampleRepo.git("add", ADDITIONAL_SOURCE_FILE);
        sampleRepo.git("commit", "--all", "--message=test");

        WorkflowRun featureBuild = buildFeature(mp, 1);

        GitCommitsRecord gitCommit = featureBuild.getAction(GitCommitsRecord.class);
        assertThat(gitCommit).isNotNull();
        assertThat(gitCommit.getCommits()).hasSize(4);

        GitBranchMasterIntersectionFinder finder = featureBuild.getAction(GitBranchMasterIntersectionFinder.class);
        assertThat(finder).isNotNull();
        assertThat(GitBranchMasterIntersectionFinder.NO_INTERSECTION_FOUND).isEqualTo(finder.getBuildId());
    }

    /**
     * Checks the Configuration skipUnknownCommits. If there are unknown commits in the master build the algorithm
     * should skip the build in search for the reference point.
     */
    @Test
    public void shouldSkipBuildWithUnknownBuildsEnabled() throws Exception {
        initializeGit("skipUnknownCommits: true");

        WorkflowMultiBranchProject mp = createMultiBranchPipeline();
        WorkflowRun masterBuild = buildMaster(mp, 1);
        verifyRecordSize(masterBuild, 2);

        sampleRepo.write(ADDITIONAL_SOURCE_FILE, "test");
        sampleRepo.git("add", ADDITIONAL_SOURCE_FILE);
        sampleRepo.git("commit", "--all", "--message=test");

        sampleRepo.git("checkout", "-b", FEATURE);
        sampleRepo.write(JENKINS_FILE,
                "echo \"branch=${env.BRANCH_NAME}\"; node {checkout scm; echo readFile('file').toUpperCase(); echo \"GitForensics\"; gitForensics skipUnknownCommits: true}");
        sampleRepo.write(SOURCE_FILE, "subsequent content");
        sampleRepo.git("commit", "--all", "--message=tweaked");

        // new master commits
        sampleRepo.git("checkout", MASTER);
        sampleRepo.write(ADDITIONAL_SOURCE_FILE, "test edit");
        sampleRepo.git("commit", "--all", "--message=edit-test");

        WorkflowRun nextMaster = buildMaster(mp, 2);
        verifyRecordSize(nextMaster, 2);

        WorkflowRun featureBuild = buildFeature(mp, 1);

        GitCommitsRecord gitCommit = featureBuild.getAction(GitCommitsRecord.class);
        assertThat(gitCommit).isNotNull();
        assertThat(gitCommit.getCommits()).hasSize(4);

        GitBranchMasterIntersectionFinder finder = featureBuild.getAction(GitBranchMasterIntersectionFinder.class);
        assertThat(finder).isNotNull();
        assertThat("p/master#1").isEqualTo(finder.getBuildId());
    }

    /**
     * Checks the configuration latestBuildIfNotFound. If there is no intersection found (in this case due to
     * insufficient maxCommits) then the newest Build of the master job should be taken as reference point.
     */
    @Test
    public void shouldUseNewestBuildIfNewestBuildIfNotFoundIsEnabled() throws Exception {
        initializeGit("maxCommits: 2", "latestBuildIfNotFound: true");

        WorkflowMultiBranchProject mp = createMultiBranchPipeline();
        WorkflowRun masterBuild = buildMaster(mp, 1);
        verifyRecordSize(masterBuild, 2);

        sampleRepo.write("testfile.txt", "testfile");
        sampleRepo.git("add", "testfile.txt");
        sampleRepo.git("commit", "--all", "--message=testfile");

        WorkflowRun nextMaster = buildMaster(mp, 2);
        verifyRecordSize(nextMaster, 1);

        sampleRepo.git("checkout", "-b", FEATURE);
        sampleRepo.write(JENKINS_FILE,
                "echo \"branch=${env.BRANCH_NAME}\"; node {checkout scm; echo readFile('file').toUpperCase(); echo \"GitForensics\"; gitForensics maxCommits: 2, latestBuildIfNotFound: true}");
        sampleRepo.write(SOURCE_FILE, "subsequent content");
        sampleRepo.git("commit", "--all", "--message=tweaked");
        // Second Commit
        sampleRepo.write(ADDITIONAL_SOURCE_FILE, "Test");
        sampleRepo.git("add", ADDITIONAL_SOURCE_FILE);
        sampleRepo.git("commit", "--all", "--message=test");

        WorkflowRun featureBuild = buildFeature(mp, 1);

        GitCommitsRecord gitCommit = featureBuild.getAction(GitCommitsRecord.class);
        assertThat(gitCommit).isNotNull();
        assertThat(gitCommit.getCommits()).hasSize(5);

        GitBranchMasterIntersectionFinder finder = featureBuild.getAction(GitBranchMasterIntersectionFinder.class);
        assertThat(finder).isNotNull();
        assertThat(finder.getBuildId()).isEqualTo("p/master#2");
    }

    /**
     * Checks if the correct intersection point is found when there are multiple feature branches and one is checked out
     * not from the master but from one of the other feature branches.
     */
    @Test
    public void shouldFindMasterReferenceIfBranchIsCheckedOutFromAnotherFeatureBranch() throws Exception {
        initializeGit();

        WorkflowMultiBranchProject mp = createMultiBranchPipeline();
        WorkflowRun masterBuild = buildMaster(mp, 1);
        verifyRecordSize(masterBuild, 2);

        createFeatureBranchAndAddCommits();

        WorkflowRun featureBuild = buildFeature(mp, 1);

        // Check this plugin
        GitCommitsRecord gitCommit = featureBuild.getAction(GitCommitsRecord.class);
        assertThat(gitCommit).isNotNull();
        assertThat(gitCommit.getCommits()).hasSize(3);

        // Now the second branch
        sampleRepo.git("checkout", "-b", "feature2");
        sampleRepo.write(ADDITIONAL_SOURCE_FILE, "my second feature");
        sampleRepo.git("add", ADDITIONAL_SOURCE_FILE);
        sampleRepo.git("commit", "--all", "--message=secondFeature");

        WorkflowRun anotherBranch = buildBranch(mp, "feature2");

        // Found correct intersection? (master and not the first feature branch)
        GitBranchMasterIntersectionFinder finder = anotherBranch.getAction(GitBranchMasterIntersectionFinder.class);
        assertThat(finder).isNotNull();
        assertThat("p/master#1").isEqualTo(finder.getBuildId());
    }

    /**
     * Tests if the Intersection point is not found if the build is deleted.
     */
    @Test
    public void shouldNotFindIntersectionIfBuildWasDeleted2() throws Exception {
        initializeGit();

        WorkflowMultiBranchProject mp = createMultiBranchPipeline();

        WorkflowRun toDelete = buildMaster(mp, 1);
        verifyRecordSize(toDelete, 2);

        String toDeleteId = toDelete.getExternalizableId();
        assertThat(toDelete.getNumber()).isEqualTo(1);

        createFeatureBranchAndAddCommits();

        // New master commits
        sampleRepo.git("checkout", MASTER);
        sampleRepo.write("testfile.txt", "testfile");
        sampleRepo.git("add", "testfile.txt");
        sampleRepo.git("commit", "--all", "--message=testfile");

        // Second master build
        QueueTaskFuture<WorkflowRun> workflowRunQueueTaskFuture = toDelete.getParent().scheduleBuild2(0);
        WorkflowRun nextMaster = workflowRunQueueTaskFuture.get();
        verifyRecordSize(nextMaster, 1);

        // Now delete Build before the feature branch is build.
        Objects.requireNonNull(Run.fromExternalizableId(toDeleteId)).delete();

        WorkflowRun featureBuild = buildFeature(mp, 1);

        GitCommitsRecord gitCommit = featureBuild.getAction(GitCommitsRecord.class);
        assertThat(gitCommit).isNotNull();
        assertThat(gitCommit.getCommits()).hasSize(3);

        GitBranchMasterIntersectionFinder finder = featureBuild.getAction(GitBranchMasterIntersectionFinder.class);
        assertThat(finder).isNotNull();
        assertThat(finder.getBuildId()).isEqualTo(GitBranchMasterIntersectionFinder.NO_INTERSECTION_FOUND);
    }



    /**
     * If the Intersection point is not found due to the build being deleted the newest master build should be taken
     * with latestBuildIfNotFound enabled.
     */
    @Test
    @Ignore
    public void shouldTakeNewestMasterBuildIfBuildWasDeletedAndNewestBuildIfNotFoundIsEnabled() throws Exception {
        initializeGitRepository("latestBuildIfNotFound: true");

        WorkflowMultiBranchProject mp = createMultiBranchPipeline();

        WorkflowRun toDelete = buildMaster(mp, 1);
        verifyRecordSize(toDelete, 2);

        String toDeleteId = toDelete.getExternalizableId();
        assertThat(toDelete.getNumber()).isEqualTo(1);

        GitCommitsRecord gitCommit = toDelete.getAction(GitCommitsRecord.class);
        assertThat(gitCommit).isNotNull();
        assertThat(gitCommit.getCommits()).hasSize(2);

        sampleRepo.git("checkout", "-b", FEATURE);
        sampleRepo.write(JENKINS_FILE,
                "echo \"branch=${env.BRANCH_NAME}\"; node {checkout scm; echo readFile('file').toUpperCase(); echo \"GitForensics\"; gitForensics latestBuildIfNotFound: true}");
        sampleRepo.write(SOURCE_FILE, "subsequent content");
        sampleRepo.git("commit", "--all", "--message=tweaked");

        // New master commits
        sampleRepo.git("checkout", MASTER);
        sampleRepo.write("testfile.txt", "testfile");
        sampleRepo.git("add", "testfile.txt");
        sampleRepo.git("commit", "--all", "--message=testfile");

        WorkflowRun nextMaster = buildMaster(mp, 2);
        verifyRecordSize(nextMaster, 1);

        // Check this plugin
        gitCommit = nextMaster.getAction(GitCommitsRecord.class);
        assertThat(gitCommit).isNotNull();
        assertThat(gitCommit.getCommits()).hasSize(1);

        // Now delete Build before the feature branch is build.
        Objects.requireNonNull(Run.fromExternalizableId(toDeleteId)).delete();

        WorkflowRun featureBuild = buildFeature(mp, 1);

        // Check this plugin
        gitCommit = featureBuild.getAction(GitCommitsRecord.class);
        assertThat(gitCommit).isNotNull();
        assertThat(gitCommit.getCommits()).hasSize(3);

        // Found correct intersection?
        GitBranchMasterIntersectionFinder finder = featureBuild.getAction(GitBranchMasterIntersectionFinder.class);
        assertThat(finder).isNotNull();
        assertThat(finder.getBuildId()).isEqualTo(nextMaster.getExternalizableId());
    }

    public static WorkflowJob scheduleAndFindBranchProject(WorkflowMultiBranchProject mp, String name)
            throws Exception {
        mp.scheduleBuild2(0).getFuture().get();
        return findBranchProject(mp, name);
    }

    public static WorkflowJob findBranchProject(WorkflowMultiBranchProject mp, String name) throws Exception {
        WorkflowJob p = mp.getItem(name);
        showIndexing(mp);
        if (p == null) {
            fail(name + " project not found");
        }
        return p;
    }

    static void showIndexing(WorkflowMultiBranchProject mp) throws Exception {
        FolderComputation<?> indexing = mp.getIndexing();
        System.out.println("---%<--- " + indexing.getUrl());
        indexing.writeWholeLogTo(System.out);
        System.out.println("---%<--- ");
    }
}