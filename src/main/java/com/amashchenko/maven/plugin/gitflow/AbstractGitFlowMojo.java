/*
 * Copyright 2014-2021 Aleksandr Mashchenko.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.amashchenko.maven.plugin.gitflow;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.TimeZone;
import java.util.regex.Pattern;

import org.apache.commons.lang3.SystemUtils;
import org.apache.maven.artifact.ArtifactUtils;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingResult;
import org.apache.maven.settings.Settings;
import org.codehaus.plexus.components.interactivity.Prompter;
import org.codehaus.plexus.util.StringUtils;
import org.codehaus.plexus.util.cli.CommandLineException;
import org.codehaus.plexus.util.cli.CommandLineUtils;
import org.codehaus.plexus.util.cli.Commandline;

/**
 * Abstract git flow mojo.
 * 
 */
public abstract class AbstractGitFlowMojo extends AbstractMojo {
    /** A full name of the versions-maven-plugin set goal. */
    private static final String VERSIONS_MAVEN_PLUGIN_SET_GOAL = "org.codehaus.mojo:versions-maven-plugin:set";
    /** A full name of the versions-maven-plugin set-property goal. */
    private static final String VERSIONS_MAVEN_PLUGIN_SET_PROPERTY_GOAL = "org.codehaus.mojo:versions-maven-plugin:set-property";
    /** Name of the tycho-versions-plugin set-version goal. */
    private static final String TYCHO_VERSIONS_PLUGIN_SET_GOAL = "org.eclipse.tycho:tycho-versions-plugin:set-version";
    /** Name of the property needed to have reproducible builds. */
    private static final String REPRODUCIBLE_BUILDS_PROPERTY = "project.build.outputTimestamp";

    /** System line separator. */
    protected static final String LS = System.getProperty("line.separator");

    /** Success exit code. */
    private static final int SUCCESS_EXIT_CODE = 0;

    /** Pattern of disallowed characters in Maven commands. */
    private static final Pattern MAVEN_DISALLOWED_PATTERN = Pattern
            .compile("[&|;]");

    /** Command line for Git executable. */
    private final Commandline cmdGit = new Commandline();
    /** Command line for Maven executable. */
    private final Commandline cmdMvn = new Commandline();

    /** Git flow configuration. */
    @Parameter(defaultValue = "${gitFlowConfig}")
    protected GitFlowConfig gitFlowConfig;

    /**
     * Git commit messages.
     * 
     * @since 1.2.1
     */
    @Parameter(defaultValue = "${commitMessages}")
    protected CommitMessages commitMessages;

    /**
     * Whether this is Tycho build.
     * 
     * @since 1.1.0
     */
    @Parameter(defaultValue = "false")
    protected boolean tychoBuild;

    /**
     * Whether to call Maven install goal during the mojo execution.
     * 
     * @since 1.0.5
     */
    @Parameter(property = "installProject", defaultValue = "false")
    protected boolean installProject = false;

    /**
     * Whether to fetch remote branch and compare it with the local one.
     * 
     * @since 1.3.0
     */
    @Parameter(property = "fetchRemote", defaultValue = "true")
    protected boolean fetchRemote;

    /**
     * Whether to print commands output into the console.
     * 
     * @since 1.0.7
     */
    @Parameter(property = "verbose", defaultValue = "false")
    private boolean verbose = false;

    /**
     * Command line arguments to pass to the underlying Maven commands.
     * 
     * @since 1.8.0
     */
    @Parameter(property = "argLine")
    private String argLine;

    /**
     * Stores the original argLine.
     * If branch based properties are needed, this will be used as reference
     * for the argLine manipulation.
     */
    private String argLineOrig;

    /**
     * Whether to make a GPG-signed commit.
     * 
     * @since 1.9.0
     */
    @Parameter(property = "gpgSignCommit", defaultValue = "false")
    private boolean gpgSignCommit = false;

    /**
     * Whether to set -DgroupId='*' -DartifactId='*' when calling
     * versions-maven-plugin.
     * 
     * @since 1.10.0
     */
    @Parameter(property = "versionsForceUpdate", defaultValue = "false")
    private boolean versionsForceUpdate = false;

    /**
     * Property to set version to.
     *
     * @since 1.13.0
     */
    @Parameter(property = "versionProperty")
    private String versionProperty;

    /**
     * Property to treat as <code>changelist</code> property.
     * Used for Maven CI friendly versioning handling. Only relevant in conjunction
     * with the <code>xxxChangelistValue</code>'s.
     * 
     * @since 1.17.0
     */
    @Parameter(property = "changelistProperty", defaultValue = "changelist")
    private String changelistProperty;

    /**
     * The value to pass as <code>changelist</code> value when running on the
     * production branch.
     * 
     * @since 1.17.0
     */
    @Parameter(property = "productionChangelistValue")
    private String productionChangelistValue;

    /**
     * The value to pass as <code>changelist</code> value when running on the
     * hotfix branch.
     * 
     * @since 1.17.0
     */
    @Parameter(property = "hotfixChangelistValue")
    private String hotfixChangelistValue;

    /**
     * The value to pass as <code>changelist</code> value when running on the
     * release branch.
     * 
     * @since 1.17.0
     */
    @Parameter(property = "releaseChangelistValue")
    private String releaseChangelistValue;

    /**
     * The value to pass as <code>changelist</code> value when running on the
     * development branch.
     * 
     * @since 1.17.0
     */
    @Parameter(property = "developmentChangelistValue")
    private String developmentChangelistValue;

    /**
     * The value to pass as <code>changelist</code> value when running on the
     * feature branch.
     * 
     * @since 1.17.0
     */
    @Parameter(property = "featureChangelistValue")
    private String featureChangelistValue;

    /**
     * The value to pass as <code>changelist</code> value when running on the
     * support branch.
     * 
     * @since 1.17.0
     */
    @Parameter(property = "supportChangelistValue")
    private String supportChangelistValue;

    /**
     * Whether to skip updating version. Useful with {@link #versionProperty} to be
     * able to update <code>revision</code> property without modifying version tag.
     * 
     * @since 1.13.0
     */
    @Parameter(property = "skipUpdateVersion")
    private boolean skipUpdateVersion = false;

    /**
     * Prefix that is applied to commit messages.
     * 
     * @since 1.14.0
     */
    @Parameter(property = "commitMessagePrefix")
    private String commitMessagePrefix;

    /**
     * The path to the Maven executable. Defaults to "mvn".
     */
    @Parameter(property = "mvnExecutable")
    private String mvnExecutable;

    /**
     * The path to the Git executable. Defaults to "git".
     */
    @Parameter(property = "gitExecutable")
    private String gitExecutable;

    /** Maven session. */
    @Parameter(defaultValue = "${session}", readonly = true)
    protected MavenSession mavenSession;

    /**
     * Whether to update the <code>project.build.outputTimestamp</code> property automatically or not.
     *
     * @since 1.16.1
     */
    @Parameter(property = "updateOutputTimestamp", defaultValue = "true")
    private boolean updateOutputTimestamp = true;

    @Component
    protected ProjectBuilder projectBuilder;

    /** Default prompter. */
    @Component
    protected Prompter prompter;
    /** Maven settings. */
    @Parameter(defaultValue = "${settings}", readonly = true)
    protected Settings settings;

    /**
     * Initializes command line executables.
     * 
     */
    private void initExecutables() {
        if (StringUtils.isBlank(cmdMvn.getExecutable())) {
            if (StringUtils.isBlank(mvnExecutable)) {
                final String javaCommand = mavenSession.getSystemProperties().getProperty("sun.java.command", "");
                final boolean wrapper = javaCommand.startsWith("org.apache.maven.wrapper.MavenWrapperMain");

                if (wrapper) {
                    mvnExecutable = "." + SystemUtils.FILE_SEPARATOR + "mvnw";
                } else {
                    mvnExecutable = "mvn";
                }
            }
            cmdMvn.setExecutable(mvnExecutable);
        }
        if (StringUtils.isBlank(cmdGit.getExecutable())) {
            if (StringUtils.isBlank(gitExecutable)) {
                gitExecutable = "git";
            }
            cmdGit.setExecutable(gitExecutable);
        }
    }

    /**
     * Validates plugin configuration. Throws exception if configuration is not
     * valid.
     * 
     * @param params
     *            Configuration parameters to validate.
     * @throws MojoFailureException
     *             If configuration is not valid.
     */
    protected void validateConfiguration(String... params)
            throws MojoFailureException {
        if (StringUtils.isNotBlank(argLine)
                && MAVEN_DISALLOWED_PATTERN.matcher(argLine).find()) {
            throw new MojoFailureException(
                    "The argLine doesn't match allowed pattern.");
        }
        if (params != null && params.length > 0) {
            for (String p : params) {
                if (StringUtils.isNotBlank(p)
                        && MAVEN_DISALLOWED_PATTERN.matcher(p).find()) {
                    throw new MojoFailureException("The '" + p
                            + "' value doesn't match allowed pattern.");
                }
            }
        }
    }

    /**
     * Gets current project version from pom.xml file.
     * 
     * @return Current project version.
     * @throws MojoFailureException
     */
    protected String getCurrentProjectVersion() throws MojoFailureException {
        final MavenProject reloadedProject = reloadProject(mavenSession.getCurrentProject());
        if (reloadedProject.getVersion() == null) {
            throw new MojoFailureException(
                    "Cannot get current project version. This plugin should be executed from the parent project.");
        }
        return reloadedProject.getVersion();
    }

    /**
     * Gets current project {@link #REPRODUCIBLE_BUILDS_PROPERTY} property value
     * from pom.xml file.
     * 
     * @return Value of {@link #REPRODUCIBLE_BUILDS_PROPERTY} property.
     * @throws MojoFailureException
     */
    private String getCurrentProjectOutputTimestamp() throws MojoFailureException {
        final MavenProject reloadedProject = reloadProject(mavenSession.getCurrentProject());
        return reloadedProject.getProperties().getProperty(REPRODUCIBLE_BUILDS_PROPERTY);
    }

    /**
     * Reloads project info from file
     * 
     * @param project
     * @return
     * @throws MojoFailureException
     */
    private MavenProject reloadProject(MavenProject project) throws MojoFailureException {
        try {
            ProjectBuildingResult result = projectBuilder.build(project.getFile(), mavenSession.getProjectBuildingRequest());
            return result.getProject();
        } catch (Exception e) {
            throw new MojoFailureException("Error re-loading project info", e);
        }
    }

    /**
     * Compares the production branch name with the development branch name.
     * 
     * @return <code>true</code> if the production branch name is different from
     *         the development branch name, <code>false</code> otherwise.
     */
    protected boolean notSameProdDevName() {
        return !gitFlowConfig.getProductionBranch().equals(
                gitFlowConfig.getDevelopmentBranch());
    }

    /**
     * Checks uncommitted changes.
     * 
     * @throws MojoFailureException
     * @throws CommandLineException
     */
    protected void checkUncommittedChanges() throws MojoFailureException,
            CommandLineException {
        getLog().info("Checking for uncommitted changes.");
        if (executeGitHasUncommitted()) {
            throw new MojoFailureException(
                    "You have some uncommitted files. Commit or discard local changes in order to proceed.");
        }
    }

    protected void checkSnapshotDependencies() throws MojoFailureException {
        getLog().info("Checking for SNAPSHOT versions in dependencies.");

        List<String> snapshots = new ArrayList<String>();
        List<String> builtArtifacts = new ArrayList<String>();

        List<MavenProject> projects = mavenSession.getProjects();
        for (MavenProject project : projects) {
            final MavenProject reloadedProject = reloadProject(project);

            builtArtifacts.add(reloadedProject.getGroupId() + ":" + reloadedProject.getArtifactId() + ":" + reloadedProject.getVersion());

            List<Dependency> dependencies = reloadedProject.getDependencies();
            for (Dependency d : dependencies) {
                String id = d.getGroupId() + ":" + d.getArtifactId() + ":" + d.getVersion();
                if (!builtArtifacts.contains(id) && ArtifactUtils.isSnapshot(d.getVersion())) {
                    snapshots.add(reloadedProject + " -> " + d);
                }
            }
        }

        if (!snapshots.isEmpty()) {
            for (String s : snapshots) {
                getLog().warn(s);
            }
            throw new MojoFailureException(
                    "There is some SNAPSHOT dependencies in the project, see warnings above."
                    + " Change them or ignore with `allowSnapshots` property.");
        }
    }

    /**
     * Checks if branch name is acceptable.
     * 
     * @param branchName
     *            Branch name to check.
     * @return <code>true</code> when name is valid, <code>false</code>
     *         otherwise.
     * @throws MojoFailureException
     * @throws CommandLineException
     */
    protected boolean validBranchName(final String branchName)
            throws MojoFailureException, CommandLineException {
        CommandResult res = executeGitCommandExitCode("check-ref-format",
                "--allow-onelevel", branchName);
        return res.getExitCode() == SUCCESS_EXIT_CODE;
    }

    /**
     * Executes git commands to check for uncommitted changes.
     * 
     * @return <code>true</code> when there are uncommitted changes,
     *         <code>false</code> otherwise.
     * @throws CommandLineException
     * @throws MojoFailureException
     */
    private boolean executeGitHasUncommitted() throws MojoFailureException,
            CommandLineException {
        boolean uncommited = false;

        // 1 if there were differences and 0 means no differences

        // git diff --no-ext-diff --ignore-submodules --quiet --exit-code
        final CommandResult diffCommandResult = executeGitCommandExitCode(
                "diff", "--no-ext-diff", "--ignore-submodules", "--quiet",
                "--exit-code");

        String error = null;

        if (diffCommandResult.getExitCode() == SUCCESS_EXIT_CODE) {
            // git diff-index --cached --quiet --ignore-submodules HEAD --
            final CommandResult diffIndexCommandResult = executeGitCommandExitCode(
                    "diff-index", "--cached", "--quiet", "--ignore-submodules",
                    "HEAD", "--");
            if (diffIndexCommandResult.getExitCode() != SUCCESS_EXIT_CODE) {
                error = diffIndexCommandResult.getError();
                uncommited = true;
            }
        } else {
            error = diffCommandResult.getError();
            uncommited = true;
        }

        if (StringUtils.isNotBlank(error)) {
            throw new MojoFailureException(error);
        }

        return uncommited;
    }

    /**
     * Executes git config commands to set Git Flow configuration.
     * 
     * @throws MojoFailureException
     * @throws CommandLineException
     */
    protected void initGitFlowConfig() throws MojoFailureException,
            CommandLineException {
        gitSetConfig("gitflow.branch.master",
                gitFlowConfig.getProductionBranch());
        gitSetConfig("gitflow.branch.develop",
                gitFlowConfig.getDevelopmentBranch());

        gitSetConfig("gitflow.prefix.feature",
                gitFlowConfig.getFeatureBranchPrefix());
        gitSetConfig("gitflow.prefix.release",
                gitFlowConfig.getReleaseBranchPrefix());
        gitSetConfig("gitflow.prefix.hotfix",
                gitFlowConfig.getHotfixBranchPrefix());
        gitSetConfig("gitflow.prefix.support",
                gitFlowConfig.getSupportBranchPrefix());
        gitSetConfig("gitflow.prefix.versiontag",
                gitFlowConfig.getVersionTagPrefix());

        gitSetConfig("gitflow.origin", gitFlowConfig.getOrigin());
    }

    /**
     * Executes git config command.
     * 
     * @param name
     *            Option name.
     * @param value
     *            Option value.
     * @throws MojoFailureException
     * @throws CommandLineException
     */
    private void gitSetConfig(final String name, String value)
            throws MojoFailureException, CommandLineException {
        if (value == null || value.isEmpty()) {
            value = "\"\"";
        }

        // ignore error exit codes
        executeGitCommandExitCode("config", name, value);
    }

    /**
     * Executes git for-each-ref with <code>refname:short</code> format.
     * 
     * @param branchName
     *            Branch name to find.
     * @param firstMatch
     *            Return first match.
     * @return Branch names which matches <code>refs/heads/{branchName}*</code>.
     * @throws MojoFailureException
     * @throws CommandLineException
     */
    protected String gitFindBranches(final String branchName, final boolean firstMatch)
            throws MojoFailureException, CommandLineException {
        return gitFindBranches("refs/heads/", branchName, firstMatch);
    }

    /**
     * Executes git for-each-ref with <code>refname:short</code> format.
     * 
     * @param refs
     *            Refs to search.
     * @param branchName
     *            Branch name to find.
     * @param firstMatch
     *            Return first match.
     * @return Branch names which matches <code>{refs}{branchName}*</code>.
     * @throws MojoFailureException
     * @throws CommandLineException
     */
    private String gitFindBranches(final String refs, final String branchName,
            final boolean firstMatch) throws MojoFailureException,
            CommandLineException {
        String wildcard = "*";
        if (branchName.endsWith("/")) {
            wildcard = "**";
        }

        String branches;
        if (firstMatch) {
            branches = executeGitCommandReturn("for-each-ref", "--count=1",
                    "--format=\"%(refname:short)\"", refs + branchName + wildcard);
        } else {
            branches = executeGitCommandReturn("for-each-ref",
                    "--format=\"%(refname:short)\"", refs + branchName + wildcard);
        }

        // on *nix systems return values from git for-each-ref are wrapped in
        // quotes
        // https://github.com/aleksandr-m/gitflow-maven-plugin/issues/3
        branches = removeQuotes(branches);
        branches = StringUtils.strip(branches);

        return branches;
    }

    /**
     * Executes git for-each-ref to get all tags.
     *
     * @return Git tags.
     * @throws MojoFailureException
     * @throws CommandLineException
     */
    protected String gitFindTags() throws MojoFailureException, CommandLineException {
        String tags = executeGitCommandReturn("for-each-ref", "--sort=*authordate", "--format=\"%(refname:short)\"",
                "refs/tags/");
        // https://github.com/aleksandr-m/gitflow-maven-plugin/issues/3
        tags = removeQuotes(tags);
        return tags;
    }

    /**
     * Executes git for-each-ref to get the last tag.
     *
     * @return Last tag.
     * @throws MojoFailureException
     * @throws CommandLineException
     */
    protected String gitFindLastTag() throws MojoFailureException, CommandLineException {
        String tag = executeGitCommandReturn("for-each-ref", "--sort=\"-version:refname\"", "--sort=-taggerdate",
                "--count=1", "--format=\"%(refname:short)\"", "refs/tags/");
        // https://github.com/aleksandr-m/gitflow-maven-plugin/issues/3
        tag = removeQuotes(tag);
        tag = tag.replaceAll("\\r?\\n", "");
        return tag;
    }

    /**
     * Removes double quotes from the string.
     * 
     * @param str
     *            String to remove quotes from.
     * @return String without quotes.
     */
    private String removeQuotes(String str) {
        if (str != null && !str.isEmpty()) {
            str = str.replaceAll("\"", "");
        }
        return str;
    }

    /**
     * Gets the current branch name.
     * 
     * @return Current branch name.
     * @throws MojoFailureException
     * @throws CommandLineException
     */
    protected String gitCurrentBranch() throws MojoFailureException, CommandLineException {
        String name = executeGitCommandReturn("symbolic-ref", "-q", "--short", "HEAD");
        name = StringUtils.strip(name);
        return name;
    }

    /**
     * Checks if local branch with given name exists.
     *
     * @param branchName
     *            Name of the branch to check.
     * @return <code>true</code> if local branch exists, <code>false</code>
     *         otherwise.
     * @throws MojoFailureException
     * @throws CommandLineException
     */
    protected boolean gitCheckBranchExists(final String branchName)
            throws MojoFailureException, CommandLineException {
        CommandResult commandResult = executeGitCommandExitCode("show-ref",
                "--verify", "--quiet", "refs/heads/" + branchName);
        return commandResult.getExitCode() == SUCCESS_EXIT_CODE;
    }

    /**
     * Checks if local tag with given name exists.
     *
     * @param tagName
     *            Name of the tag to check.
     * @return <code>true</code> if local tag exists, <code>false</code> otherwise.
     * @throws MojoFailureException
     * @throws CommandLineException
     */
    protected boolean gitCheckTagExists(final String tagName) throws MojoFailureException, CommandLineException {
        CommandResult commandResult = executeGitCommandExitCode("show-ref", "--verify", "--quiet",
                "refs/tags/" + tagName);
        return commandResult.getExitCode() == SUCCESS_EXIT_CODE;
    }

    /**
     * Executes git checkout.
     *
     * @param branchName
     *            Branch name to checkout.
     * @throws MojoFailureException
     * @throws CommandLineException
     */
    private void gitCheckout(final String branchName)
            throws MojoFailureException, CommandLineException {
        getLog().info("Checking out '" + branchName + "' branch.");

        executeGitCommand("checkout", branchName);
    }

    /**
     * Executes git checkout -b.
     *
     * @param newBranchName
     *            Create branch with this name.
     * @param fromBranchName
     *            Create branch from this branch.
     * @throws MojoFailureException
     * @throws CommandLineException
     */
    private void gitCreateAndCheckout(final String newBranchName,
            final String fromBranchName) throws MojoFailureException,
            CommandLineException {
        getLog().info(
                "Creating a new branch '" + newBranchName + "' from '"
                        + fromBranchName + "' and checking it out.");

        executeGitCommand("checkout", "-b", newBranchName, fromBranchName);
    }

    /**
     * Executes git branch.
     *
     * @param newBranchName
     *            Create branch with this name.
     * @param fromBranchName
     *            Create branch from this branch.
     * @throws MojoFailureException
     * @throws CommandLineException
     */
    protected void gitCreateBranch(final String newBranchName, final String fromBranchName)
            throws MojoFailureException, CommandLineException {
        getLog().info(
                "Creating a new branch '" + newBranchName + "' from '"
                        + fromBranchName + "'.");

        executeGitCommand("branch", newBranchName, fromBranchName);
    }

    /**
     * Replaces properties in message.
     * 
     * @param message
     * @param map
     *            Key is a string to replace wrapped in <code>@{...}</code>. Value
     *            is a string to replace with.
     * @return
     */
    private String replaceProperties(String message, Map<String, String> map) {
        if (map != null) {
            for (Entry<String, String> entr : map.entrySet()) {
                message = StringUtils.replace(message, "@{" + entr.getKey() + "}", entr.getValue());
            }
        }
        return message;
    }

    /**
     * Executes git commit -a -m.
     * 
     * @param message
     *            Commit message.
     * @throws MojoFailureException
     * @throws CommandLineException
     */
    protected void gitCommit(final String message) throws MojoFailureException,
            CommandLineException {
        gitCommit(message, null);
    }

    /**
     * Executes git commit -a -m, replacing <code>@{map.key}</code> with
     * <code>map.value</code>.
     * 
     * @param message
     *            Commit message.
     * @param messageProperties
     *            Properties to replace in message.
     * @throws MojoFailureException
     * @throws CommandLineException
     */
    protected void gitCommit(String message, Map<String, String> messageProperties)
            throws MojoFailureException, CommandLineException {
        if (StringUtils.isNotBlank(commitMessagePrefix)) {
            message = commitMessagePrefix + message;
        }

        message = replaceProperties(message, messageProperties);

        if (gpgSignCommit) {
            getLog().info("Committing changes. GPG-signed.");

            executeGitCommand("commit", "-a", "-S", "-m", message);
        } else {
            getLog().info("Committing changes.");

            executeGitCommand("commit", "-a", "-m", message);
        }
    }

    /**
     * Executes git rebase or git merge --ff-only or git merge --no-ff or git merge.
     * 
     * @param branchName
     *            Branch name to merge.
     * @param rebase
     *            Do rebase.
     * @param noff
     *            Merge with --no-ff.
     * @param ffonly
     *            Merge with --ff-only.
     * @param message
     *            Merge commit message.
     * @param messageProperties
     *            Properties to replace in message.
     * @throws MojoFailureException
     * @throws CommandLineException
     */
    protected void gitMerge(final String branchName, boolean rebase, boolean noff, boolean ffonly, String message,
            Map<String, String> messageProperties)
            throws MojoFailureException, CommandLineException {
        String sign = "";
        if (gpgSignCommit) {
            sign = "-S";
        }
        String msgParam = "";
        String msg = "";
        if (StringUtils.isNotBlank(message)) {
            if (StringUtils.isNotBlank(commitMessagePrefix)) {
                message = commitMessagePrefix + message;
            }

            msgParam = "-m";
            msg = replaceProperties(message, messageProperties);
        }
        if (rebase) {
            getLog().info("Rebasing '" + branchName + "' branch.");
            executeGitCommand("rebase", sign, branchName);
        } else if (ffonly) {
            getLog().info("Merging (--ff-only) '" + branchName + "' branch.");
            executeGitCommand("merge", "--ff-only", sign, branchName);
        } else if (noff) {
            getLog().info("Merging (--no-ff) '" + branchName + "' branch.");
            executeGitCommand("merge", "--no-ff", sign, branchName, msgParam, msg);
        } else {
            getLog().info("Merging '" + branchName + "' branch.");
            executeGitCommand("merge", sign, branchName, msgParam, msg);
        }
    }

    /**
     * Executes git merge --no-ff.
     * 
     * @param branchName
     *            Branch name to merge.
     * @param message
     *            Merge commit message.
     * @param messageProperties
     *            Properties to replace in message.
     * @throws MojoFailureException
     * @throws CommandLineException
     */
    protected void gitMergeNoff(final String branchName, final String message,
            final Map<String, String> messageProperties)
            throws MojoFailureException, CommandLineException {
        gitMerge(branchName, false, true, false, message, messageProperties);
    }

    /**
     * Executes git merge --squash.
     * 
     * @param branchName
     *            Branch name to merge.
     * @throws MojoFailureException
     * @throws CommandLineException
     */
    protected void gitMergeSquash(final String branchName)
            throws MojoFailureException, CommandLineException {
        getLog().info("Squashing '" + branchName + "' branch.");
        executeGitCommand("merge", "--squash", branchName);
    }

    /**
     * Executes git tag -a [-s] -m.
     * 
     * @param tagName
     *            Name of the tag.
     * @param message
     *            Tag message.
     * @param gpgSignTag
     *            Make a GPG-signed tag.
     * @param messageProperties
     *            Properties to replace in message.
     * @throws MojoFailureException
     * @throws CommandLineException
     */
    protected void gitTag(final String tagName, String message, boolean gpgSignTag, Map<String, String> messageProperties)
            throws MojoFailureException, CommandLineException {
        message = replaceProperties(message, messageProperties);

        if (gpgSignTag) {
            getLog().info("Creating GPG-signed '" + tagName + "' tag.");

            executeGitCommand("tag", "-a", "-s", tagName, "-m", message);
        } else {
            getLog().info("Creating '" + tagName + "' tag.");

            executeGitCommand("tag", "-a", tagName, "-m", message);
        }
    }

    /**
     * Executes git branch -d.
     * 
     * @param branchName
     *            Branch name to delete.
     * @throws MojoFailureException
     * @throws CommandLineException
     */
    protected void gitBranchDelete(final String branchName)
            throws MojoFailureException, CommandLineException {
        getLog().info("Deleting '" + branchName + "' branch.");

        executeGitCommand("branch", "-d", branchName);
    }

    /**
     * Executes git branch -D.
     * 
     * @param branchName
     *            Branch name to delete.
     * @throws MojoFailureException
     * @throws CommandLineException
     */
    protected void gitBranchDeleteForce(final String branchName)
            throws MojoFailureException, CommandLineException {
        getLog().info("Deleting (-D) '" + branchName + "' branch.");

        executeGitCommand("branch", "-D", branchName);
    }

    /**
     * Fetches and checkouts from remote if local branch doesn't exist.
     * 
     * @param branchName
     *            Branch name to check.
     * @throws MojoFailureException
     * @throws CommandLineException
     */
    protected void gitFetchRemoteAndCreate(final String branchName)
            throws MojoFailureException, CommandLineException {
        if (!gitCheckBranchExists(branchName)) {
            getLog().info(
                    "Local branch '"
                            + branchName
                            + "' doesn't exist. Trying to fetch and check it out from '"
                            + gitFlowConfig.getOrigin() + "'.");
            gitFetchRemote(branchName);
            gitCreateAndCheckout(branchName, gitFlowConfig.getOrigin() + "/"
                    + branchName);
        }
    }

    /**
     * Executes git fetch and compares local branch with the remote.
     * 
     * @param branchName
     *            Branch name to fetch and compare.
     * @throws MojoFailureException
     * @throws CommandLineException
     */
    protected void gitFetchRemoteAndCompare(final String branchName)
            throws MojoFailureException, CommandLineException {
        if (gitFetchRemote(branchName)) {
            getLog().info(
                    "Comparing local branch '" + branchName + "' with remote '"
                            + gitFlowConfig.getOrigin() + "/" + branchName
                            + "'.");
            String revlistout = executeGitCommandReturn("rev-list",
                    "--left-right", "--count", branchName + "..."
                            + gitFlowConfig.getOrigin() + "/" + branchName);

            String[] counts = org.apache.commons.lang3.StringUtils.split(
                    revlistout, '\t');
            if (counts != null && counts.length > 1) {
                if (!"0".equals(org.apache.commons.lang3.StringUtils
                        .deleteWhitespace(counts[1]))) {
                    throw new MojoFailureException("Remote branch '"
                            + gitFlowConfig.getOrigin() + "/" + branchName
                            + "' is ahead of the local branch '" + branchName
                            + "'. Execute git pull.");
                }
            }
        }
    }

    /**
     * Executes git fetch and git for-each-ref with <code>refname:short</code>
     * format. Searches <code>refs/remotes/{remoteName}/</code>.
     * 
     * @param remoteName
     *            Name of the remote.
     * @param branchName
     *            Branch name to find.
     * @param firstMatch
     *            Return first match.
     * @return Branch names which matches <code>refs/heads/{branchName}*</code>.
     * @throws MojoFailureException
     * @throws CommandLineException
     */
    protected String gitFetchAndFindRemoteBranches(final String remoteName, final String branchName,
            final boolean firstMatch) throws MojoFailureException, CommandLineException {
        gitFetchRemote();
        return gitFindBranches("refs/remotes/" + remoteName + "/", branchName, firstMatch);
    }

    /**
     * Executes git fetch.
     * 
     * @return <code>true</code> if git fetch returned success exit code,
     *         <code>false</code> otherwise.
     * @throws MojoFailureException
     * @throws CommandLineException
     */
    private boolean gitFetchRemote() throws MojoFailureException, CommandLineException {
        return gitFetchRemote("");
    }

    /**
     * Executes git fetch with specific branch.
     * 
     * @param branchName
     *            Branch name to fetch.
     * @return <code>true</code> if git fetch returned success exit code,
     *         <code>false</code> otherwise.
     * @throws MojoFailureException
     * @throws CommandLineException
     */
    private boolean gitFetchRemote(final String branchName)
            throws MojoFailureException, CommandLineException {
        getLog().info(
                "Fetching remote branch '" + gitFlowConfig.getOrigin() + " "
                        + branchName + "'.");

        CommandResult result = executeGitCommandExitCode("fetch", "--quiet",
                gitFlowConfig.getOrigin(), branchName);

        boolean success = result.getExitCode() == SUCCESS_EXIT_CODE;
        if (!success) {
            getLog().warn(
                    "There were some problems fetching remote branch '"
                            + gitFlowConfig.getOrigin()
                            + " "
                            + branchName
                            + "'. You can turn off remote branch fetching by setting the 'fetchRemote' parameter to false.");
        }

        return success;
    }

    /**
     * Executes git push, optionally with the <code>--follow-tags</code>
     * argument.
     * 
     * @param branchName
     *            Branch name to push.
     * @param pushTags
     *            If <code>true</code> adds <code>--follow-tags</code> argument
     *            to the git <code>push</code> command.
     * @throws MojoFailureException
     * @throws CommandLineException
     */
    protected void gitPush(final String branchName, boolean pushTags)
            throws MojoFailureException, CommandLineException {
        getLog().info(
                "Pushing '" + branchName + "' branch" + " to '"
                        + gitFlowConfig.getOrigin() + "'.");

        if (pushTags) {
            executeGitCommand("push", "--quiet", "-u", "--follow-tags",
                    gitFlowConfig.getOrigin(), branchName);
        } else {
            executeGitCommand("push", "--quiet", "-u",
                    gitFlowConfig.getOrigin(), branchName);
        }
    }

    protected void gitPushDelete(final String branchName)
            throws MojoFailureException, CommandLineException {
        getLog().info(
                "Deleting remote branch '" + branchName + "' from '"
                        + gitFlowConfig.getOrigin() + "'.");

        CommandResult result = executeGitCommandExitCode("push", "--delete",
                gitFlowConfig.getOrigin(), branchName);

        if (result.getExitCode() != SUCCESS_EXIT_CODE) {
            getLog().warn(
                    "There were some problems deleting remote branch '"
                            + branchName + "' from '"
                            + gitFlowConfig.getOrigin() + "'.");
        }
    }

    /**
     * Executes 'set' goal of versions-maven-plugin or 'set-version' of
     * tycho-versions-plugin in case it is tycho build.
     * 
     * @param version
     *            New version to set.
     * @throws MojoFailureException
     * @throws CommandLineException
     */
    protected void mvnSetVersions(final String version) throws MojoFailureException, CommandLineException {
        getLog().info("Updating version(s) to '" + version + "'.");

        String newVersion = "-DnewVersion=" + version;
        String grp = "";
        String art = "";
        if (versionsForceUpdate) {
            grp = "-DgroupId=";
            art = "-DartifactId=";
        }

        if (tychoBuild) {
            String prop = "";
            if (StringUtils.isNotBlank(versionProperty)) {
                prop = "-Dproperties=" + versionProperty;
                getLog().info("Updating property '" + versionProperty + "' to '" + version + "'.");
            }

            executeMvnCommand(TYCHO_VERSIONS_PLUGIN_SET_GOAL, prop, newVersion, "-Dtycho.mode=maven");
        } else {
            boolean runCommand = false;
            List<String> args = new ArrayList<>();
            args.add("-DgenerateBackupPoms=false");
            args.add(newVersion);
            if (!skipUpdateVersion) {
                runCommand = true;
                args.add(VERSIONS_MAVEN_PLUGIN_SET_GOAL);
                args.add(grp);
                args.add(art);
            }

            if (StringUtils.isNotBlank(versionProperty)) {
                runCommand = true;
                getLog().info("Updating property '" + versionProperty + "' to '" + version + "'.");

                args.add(VERSIONS_MAVEN_PLUGIN_SET_PROPERTY_GOAL);
                args.add("-Dproperty=" + versionProperty);
            }
            if (runCommand) {
                executeMvnCommand(args.toArray(new String[0]));

                if (updateOutputTimestamp) {
                    String timestamp = getCurrentProjectOutputTimestamp();
                    if (timestamp != null && timestamp.length() > 1) {
                        if (StringUtils.isNumeric(timestamp)) {
                            // int representing seconds since the epoch
                            timestamp = String.valueOf(System.currentTimeMillis() / 1000l);
                        } else {
                            // ISO-8601
                            DateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
                            df.setTimeZone(TimeZone.getTimeZone("UTC"));
                            timestamp = df.format(new Date());
                        }

                        getLog().info("Updating property '" + REPRODUCIBLE_BUILDS_PROPERTY + "' to '" + timestamp + "'.");

                        executeMvnCommand(VERSIONS_MAVEN_PLUGIN_SET_PROPERTY_GOAL, "-DgenerateBackupPoms=false",
                                "-Dproperty=" + REPRODUCIBLE_BUILDS_PROPERTY, "-DnewVersion=" + timestamp);
                    }
                }
            }
        }
    }

    /**
     * Executes mvn clean test.
     * 
     * @throws MojoFailureException
     * @throws CommandLineException
     */
    protected void mvnCleanTest() throws MojoFailureException,
            CommandLineException {
        getLog().info("Cleaning and testing the project.");
        if (tychoBuild) {
            executeMvnCommand("clean", "verify");
        } else {
            executeMvnCommand("clean", "test");
        }
    }

    /**
     * Executes mvn clean install.
     * 
     * @throws MojoFailureException
     * @throws CommandLineException
     */
    protected void mvnCleanInstall() throws MojoFailureException,
            CommandLineException {
        getLog().info("Cleaning and installing the project.");

        executeMvnCommand("clean", "install");
    }

    /**
     * Executes Maven goals.
     * 
     * @param goals
     *            The goals to execute.
     * @throws Exception
     */
    protected void mvnRun(final String goals) throws Exception {
        getLog().info("Running Maven goals: " + goals);

        executeMvnCommand(CommandLineUtils.translateCommandline(goals));
    }

    /**
     * Executes Git command and returns output.
     * 
     * @param args
     *            Git command line arguments.
     * @return Command output.
     * @throws CommandLineException
     * @throws MojoFailureException
     */
    private String executeGitCommandReturn(final String... args)
            throws CommandLineException, MojoFailureException {
        return executeCommand(cmdGit, true, null, args).getOut();
    }

    /**
     * Executes Git command without failing on non successful exit code.
     * 
     * @param args
     *            Git command line arguments.
     * @return Command result.
     * @throws CommandLineException
     * @throws MojoFailureException
     */
    private CommandResult executeGitCommandExitCode(final String... args)
            throws CommandLineException, MojoFailureException {
        return executeCommand(cmdGit, false, null, args);
    }

    /**
     * Executes Git command.
     * 
     * @param args
     *            Git command line arguments.
     * @throws CommandLineException
     * @throws MojoFailureException
     */
    private void executeGitCommand(final String... args)
            throws CommandLineException, MojoFailureException {
        executeCommand(cmdGit, true, null, args);
    }

    /**
     * Executes Maven command.
     * 
     * @param args
     *            Maven command line arguments.
     * @throws CommandLineException
     * @throws MojoFailureException
     */
    private void executeMvnCommand(final String... args)
            throws CommandLineException, MojoFailureException {
        executeCommand(cmdMvn, true, argLine, args);
    }

    /**
     * Executes command line.
     * 
     * @param cmd
     *            Command line.
     * @param failOnError
     *            Whether to throw exception on NOT success exit code.
     * @param argStr
     *            Command line arguments as a string.
     * @param args
     *            Command line arguments.
     * @return {@link CommandResult} instance holding command exit code, output
     *         and error if any.
     * @throws CommandLineException
     * @throws MojoFailureException
     *             If <code>failOnError</code> is <code>true</code> and command
     *             exit code is NOT equals to 0.
     */
    private CommandResult executeCommand(final Commandline cmd,
            final boolean failOnError, final String argStr,
            final String... args) throws CommandLineException,
            MojoFailureException {
        // initialize executables
        initExecutables();

        if (getLog().isDebugEnabled()) {
            getLog().debug(
                    cmd.getExecutable() + " " + StringUtils.join(args, " ")
                            + (argStr == null ? "" : " " + argStr));
        }

        cmd.clearArgs();
        cmd.addArguments(args);

        if (StringUtils.isNotBlank(argStr)) {
            cmd.createArg().setLine(argStr);
        }

        final StringBufferStreamConsumer out = new StringBufferStreamConsumer(
                verbose);

        final CommandLineUtils.StringStreamConsumer err = new CommandLineUtils.StringStreamConsumer();

        // execute
        final int exitCode = CommandLineUtils.executeCommandLine(cmd, out, err);

        String errorStr = err.getOutput();
        String outStr = out.getOutput();

        if (failOnError && exitCode != SUCCESS_EXIT_CODE) {
            // not all commands print errors to error stream
            if (StringUtils.isBlank(errorStr) && StringUtils.isNotBlank(outStr)) {
                errorStr = outStr;
            }

            throw new MojoFailureException(errorStr);
        }

        return new CommandResult(exitCode, outStr, errorStr);
    }

    private static class CommandResult {
        private final int exitCode;
        private final String out;
        private final String error;

        private CommandResult(final int exitCode, final String out,
                final String error) {
            this.exitCode = exitCode;
            this.out = out;
            this.error = error;
        }

        /**
         * @return the exitCode
         */
        public int getExitCode() {
            return exitCode;
        }

        /**
         * @return the out
         */
        public String getOut() {
            return out;
        }

        /**
         * @return the error
         */
        public String getError() {
            return error;
        }
    }

    public void setArgLine(String argLine) {
        this.argLine = argLine;
        this.argLineOrig = argLine;
    }

    /**
     * Executes git checkout and sets Maven CI friendly settings per branch.
     * 
     * @param branchType
     *            Branch type to set config for.
     * @param branchName
     *            Branch name to checkout.
     * @throws MojoExecutionException
     * @throws MojoFailureException
     * @throws CommandLineException
     */
    protected void checkoutAndSetConfigForBranch(final BranchType branchType, final String branchName)
            throws MojoExecutionException, MojoFailureException, CommandLineException {
        if (branchType == null) {
            throw new MojoExecutionException("INTERNAL: given BranchType is null");
        }

        gitCheckout(branchName);
        setConfigForBranchType(branchType);
    }

    /**
     * Executes git checkout -b and sets Maven CI friendly settings per branch.
     * 
     * @param branchType
     *            Branch type to set config for.
     * @param newBranchName
     *            Create branch with this name.
     * @param fromBranchName
     *            Create branch from this branch.
     * @throws MojoExecutionException
     * @throws MojoFailureException
     * @throws CommandLineException
     */
    protected void createAndCheckoutAndSetConfigForBranch(final BranchType branchType, final String newBranchName,
        final String fromBranchName) throws MojoExecutionException, MojoFailureException, CommandLineException {
        if (branchType == null) {
            throw new MojoExecutionException("INTERNAL: given BranchType is null");
        }

        gitCreateAndCheckout(newBranchName, fromBranchName);
        setConfigForBranchType(branchType);
    }

    /**
     * Sets Maven CI friendly settings dependent of the type of branch.
     * 
     * @param branchType
     *            Branch type to set config for.
     * @throws MojoExecutionException
     */
    protected void setConfigForBranchType(final BranchType branchType) throws MojoExecutionException {
        if (branchType == null) {
            throw new MojoExecutionException("INTERNAL: given BranchType is null");
        }

        final boolean noChangelistValueToBeModified = productionChangelistValue == null
                && hotfixChangelistValue == null && releaseChangelistValue == null
                && developmentChangelistValue == null && featureChangelistValue == null
                && supportChangelistValue == null;

        if (StringUtils.isBlank(changelistProperty) || noChangelistValueToBeModified) {
            return;
        }

        switch (branchType) {
            case PRODUCTION:
                setChangelistPropertyToValue(productionChangelistValue);
                break;
            case HOTFIX:
                setChangelistPropertyToValue(hotfixChangelistValue);
                break;
            case RELEASE:
                setChangelistPropertyToValue(releaseChangelistValue);
                break;
            case DEVELOPMENT:
                setChangelistPropertyToValue(developmentChangelistValue);
                break;
            case FEATURE:
                setChangelistPropertyToValue(featureChangelistValue);
                break;
            case SUPPORT:
                setChangelistPropertyToValue(supportChangelistValue);
                break;
        }
    }

    /**
     * Sets the <code>changelist</code> property globally.
     * 
     * @param changelistPropertyValue
     *            The value to set the <code>changelist</code> property to.
     *            Remove the property if the value is null.
     */
    private void setChangelistPropertyToValue(final String changelistPropertyValue) {
        setProperty(changelistProperty, changelistPropertyValue, mavenSession);
    }

    /**
     * Sets a property globally in system-properties, optionally in the properties of the
     * <code>MavenSession</code> and replaces the argLine to contain the value as well.
     * 
     * @param key
     *            The key of the property to set.
     * @param value
     *            The value of the property to set, if null, the property gets removed.
     * @param session
     *            An optional <code>MavenSession</code> can be passed, to replace the properties
     *            in it's property-lists.
     */
    private void setProperty(final String key, final String value, final MavenSession session) {
        if (StringUtils.isBlank(key)) {
            return;
        }

        if (session != null) {
            setPropertyInProperties(key, value, session.getProjectBuildingRequest().getUserProperties());
        }

        argLine = replacePropertyInArgline(key, value, argLineOrig);
    }

    /**
     * Sets a property in the <code>Properties</code>.
     * Updates the <code>Properties</code> and  manipulate the <code>argLine</code> inside
     * of the <code>Properties</code> as well.
     * 
     * @param key
     *            The key of the property to set.
     * @param value
     *            The value of the property to set, if null, the property gets removed.
     * @param properties
     *            The properties where to replace the entry.
     */
    private void setPropertyInProperties(final String key, final String value, final Properties properties) {
        if (StringUtils.isBlank(key) || properties == null) {
            return;
        }

        final String argLineFromProperty = properties.getProperty("argLine");
        final String replaced = replacePropertyInArgline(key, value, argLineFromProperty);

        if (replaced == null) {
            properties.remove("argLine");
        } else {
            properties.put("argLine", replaced);
        }

        if (value == null) {
            properties.remove(key);
        } else {
            properties.put(key, value);
        }
    }

    /**
     * Replaces/sets a property in a argLine-String.
     * 
     * @param key
     *            The key of the property to set.
     * @param value
     *            The value of the property to set, if null, the property gets removed.
     * @param argLine
     *            A argLine-representation used to replace the key.
     * @return a new argLine where the property is replaced/set.
     */
    private String replacePropertyInArgline(final String key, final String value, final String argLine) {
        final String javaProperty = "-D" + key + "=";
        final String argLinePropertyRegex = javaProperty + "\\S*";
        final String argLinePropertyReplacement = (value == null) ? "" : javaProperty + value;

        if (StringUtils.isBlank(argLine) || !argLine.contains(javaProperty)) {
            // noop: old argLine is empty or does not contain the property and no property to set
            if (StringUtils.isBlank(argLinePropertyReplacement)) {
                return argLine;
            // append: old argLine is empty or does not contain the property and property to set
            } else {
                final String argLineReadyToAppend = StringUtils.isBlank(argLine) ? "" : argLine + " ";
                return argLineReadyToAppend + argLinePropertyReplacement;
            }
        // replace or remove: old argLine contains property, replacement: new or empty
        } else {
            return argLine.replaceAll(argLinePropertyRegex, argLinePropertyReplacement);
        }
    }
}
