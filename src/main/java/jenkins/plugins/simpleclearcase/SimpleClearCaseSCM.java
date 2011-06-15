/*
 * The MIT License
 * 
 * Copyright (c) 2011, Sun Microsystems, Inc., Sam Tavakoli
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package jenkins.plugins.simpleclearcase;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import jenkins.plugins.simpleclearcase.SimpleClearCaseChangeLogSet;
import jenkins.plugins.simpleclearcase.util.DateUtil;
import jenkins.plugins.simpleclearcase.util.DebugHelper;
import jenkins.plugins.simpleclearcase.util.OsUtil;
import jenkins.plugins.simpleclearcase.util.PropertiesUtil;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.BuildListener;
import hudson.model.ModelObject;
import hudson.model.TaskListener;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Hudson;
import hudson.scm.ChangeLogParser;
import hudson.scm.PollingResult;
import hudson.scm.SCMDescriptor;
import hudson.scm.SCMRevisionState;
import hudson.scm.SCM;
import hudson.util.FormValidation;

public class SimpleClearCaseSCM extends SCM {
	
	public final static int CHANGELOGSET_ORDER = SimpleClearCaseChangeLogEntryDateComparator.DECREASING;
	
	private String loadRules;
	private String viewname;
	
	@Extension
	public static final DescriptorImpl DESCRIPTOR = new DescriptorImpl();

	@DataBoundConstructor
	public SimpleClearCaseSCM(String loadRules, String viewname) {
		this.loadRules  = loadRules;
		this.viewname   = viewname;
	}
	
	@Override
	public SCMRevisionState calcRevisionsFromBuild(AbstractBuild<?, ?> build,
			Launcher launcher, TaskListener listener) throws IOException,
			InterruptedException {
		
		if (build == null) {
			return null;
		} else if (build.getChangeSet().isEmptySet() == true) {
			// if the changeset is empty then we cant give any revision state
			return null;
		} else {
			DebugHelper.info(listener, "calcRevisionFromBuild - the build time is: %s", 
																				build.getTime().toString());
			//fetch the latest commit date from the last build for comparison 
			Date latestCommit = ((SimpleClearCaseChangeLogSet) build.getChangeSet()).getLatestCommitDate();
			
			return new SimpleClearCaseRevisionState(latestCommit);
		}
	}

	@Override
	protected PollingResult compareRemoteRevisionWith(
			AbstractProject<?, ?> project, Launcher launcher,
			FilePath workspace, TaskListener listener, SCMRevisionState baseline)
			throws IOException, InterruptedException {
		
		//if there is no baseline it means we haven't built before, hence build
		if (baseline == null) {
			DebugHelper.info(listener, "compareRemoteRevisionWith - there is no baseline, BUILD_NOW");
			return PollingResult.BUILD_NOW;
		}
		ClearTool ct = new ClearTool(launcher, listener, workspace, viewname);
		Date baselineBuiltTime = ((SimpleClearCaseRevisionState) baseline).getBuiltTime();
		DebugHelper.info(listener, "compareRemoteRevisionWith - baseline time is: " + baselineBuiltTime);
		
		Date remoteRevisionDate = ct.getLatestCommitDate(getLoadRulesAsList(), baselineBuiltTime); 
		
		// meaning that there are no more entries from the time of last build, hence we don't build
		if (remoteRevisionDate == null) {
			DebugHelper.info(listener, "compareRemoteRevisionWith - remote revision date time is null");
			return PollingResult.NO_CHANGES;
		}
		
		DebugHelper.info(listener, "compareRemoteRevisionWith - remote revision date time is: " + baselineBuiltTime);
		
		// we need a quiet period to be sure that someone isn't in the middle of a commit session. 
		// quiet time works as we compare remoteRevisionDate added with quietperiod against current time
		// if it's not before, it means that quiet period has not passed yet, which means we signal no changes
		if (DateUtil.before(remoteRevisionDate, new Date(), PropertiesUtil.getQuietPeriod()) == false) {
			return PollingResult.NO_CHANGES;
		}
		
		if (baselineBuiltTime.before(remoteRevisionDate)) {
			DebugHelper.info(listener, "compareRemoteRevisionWith - build now");
			return PollingResult.BUILD_NOW;
		} else { 
			DebugHelper.info(listener, "compareRemoteRevisionWith - no change");
			return PollingResult.NO_CHANGES;
		}
	}

	@Override
	public boolean checkout(AbstractBuild<?, ?> build, Launcher launcher,
			FilePath workspace, BuildListener listener, File changelogFile)
			throws IOException, InterruptedException {
		
		DebugHelper.info(listener, "Checkout - start");		
		ClearTool ct = new ClearTool(launcher, listener, workspace, viewname);
		
		Date latestCommitDate = null;
		
		// we don't have a latest commit date as we haven't tracked the changelog due to the lack of previous builds.
		if (build.getPreviousBuild() != null && build.getPreviousBuild().getChangeSet().isEmptySet() == false) {

			//From the previous ChangeLogSet we will fetch the date, such that the lshistory output in ClearTool
			//doesn't present information already reviewed
			SimpleClearCaseChangeLogSet previousChangeLogSet = (SimpleClearCaseChangeLogSet) build.getPreviousBuild().getChangeSet();
			latestCommitDate = previousChangeLogSet.getLatestCommitDate();
		} 

		List<SimpleClearCaseChangeLogEntry> entries = ct.lshistory(getLoadRulesAsList(), latestCommitDate);
		
		//sort the entries according to 'setting'
		Collections.sort(entries, new SimpleClearCaseChangeLogEntryDateComparator(SimpleClearCaseSCM.CHANGELOGSET_ORDER));
		
		//create the set with entries
		SimpleClearCaseChangeLogSet set = new SimpleClearCaseChangeLogSet(build, entries);
		
		return SimpleClearCaseChangeLogParser.writeChangeLog(changelogFile, set, listener);
	}
	
	@Override
	public boolean requiresWorkspaceForPolling() {
		return true;
	}
	
	@Override
	public ChangeLogParser createChangeLogParser() {
		return new SimpleClearCaseChangeLogParser();
	}
	
	/**
	 * @return
	 */
	public String getViewname() {
		return viewname;
	}

	public String getLoadRules() {
		return loadRules;
	}

	public List<String> getLoadRulesAsList() {
		return splitLoadRules(loadRules);
	}
	
	/**
	 * @param lr
	 * @return
	 */
	private static List<String> splitLoadRules(String lr) {
		//character class matches against both \r and \n, as WIN_NEWLINE defines both \r\n, we don't need
		//to refer to UNIX_NEWLINE, as it's only the \n character. [] is an character regex 
		String[] split = lr.split(String.format("[%s]+", OsUtil.WIN_NEWLINE));
		List<String> ret = new ArrayList<String>();
		
		for (String s : split) {
			if (s.length() > 0) {
				s.trim();
				ret.add(s);
			}
		}
		return ret;
	}
	
	public static class DescriptorImpl extends SCMDescriptor<SCM> implements ModelObject {

		protected DescriptorImpl() {
			super(null);
		}
		
		/* (non-Javadoc)
		 * @see hudson.model.Descriptor#getDisplayName()
		 */
		@Override
		public String getDisplayName() {
			return Messages.simpleclearcase_DisplayName();
		}
		
		public FormValidation doCheckViewname(@QueryParameter String value) throws InterruptedException, IOException {
			if (value == null || value.trim().isEmpty() == true) {
				return FormValidation.error(Messages.simpleclearcase_viewname_empty());
			}
			
			if (value.contains(" ") == true) {
				return FormValidation.error(Messages.simpleclearcase_viewname_whitespace());
			}

			Launcher launcher = Hudson.getInstance().createLauncher(TaskListener.NULL);		
			ClearTool ct = new ClearTool(launcher, null, null, value);
			
			if (ct.doesViewExist(value) == false) {
				return FormValidation.error(Messages.simpleclearcase_viewname_doesntexist());
			}
			 
			return FormValidation.ok();
		}
		
		public FormValidation doCheckLoadRules(@QueryParameter String value) {
			if (value == null || value.trim().isEmpty() == true) {
				return FormValidation.error(Messages.simpleclearcase_loadRules_empty());
			}
			
			if (value.contains(" ") == true) {
				return FormValidation.error(Messages.simpleclearcase_loadRules_whitespace());
			}
			//remove duplications and check if sizes differ 
			List<String> splittedRules = splitLoadRules(value);
			Set<String> uniqueSet = new HashSet<String>(splittedRules);

			if (uniqueSet.size() < splittedRules.size()) {
				return FormValidation.error(Messages.simpleclearcase_loadRules_duplicated_loadrule());
			}
			return FormValidation.ok();
		}
	}
}
