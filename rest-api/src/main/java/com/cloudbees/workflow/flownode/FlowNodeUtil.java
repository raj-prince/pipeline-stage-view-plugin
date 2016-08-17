
/*
 * The MIT License
 *
 * Copyright (c) 2013-2016, CloudBees, Inc.
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
package com.cloudbees.workflow.flownode;

import com.cloudbees.workflow.rest.external.JobExt;
import com.cloudbees.workflow.rest.external.RunExt;
import com.cloudbees.workflow.rest.external.StatusExt;
import com.google.common.base.Predicate;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import hudson.Extension;
import hudson.ExtensionPoint;
import hudson.model.Item;
import hudson.model.listeners.ItemListener;
import hudson.util.RunList;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.actions.ErrorAction;
import org.jenkinsci.plugins.workflow.actions.NotExecutedNodeAction;
import org.jenkinsci.plugins.workflow.actions.TimingAction;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.graphanalysis.ForkScanner;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.support.actions.PauseAction;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

/**
 * @author <a href="mailto:tom.fennelly@gmail.com">tom.fennelly@gmail.com</a>
 */
public class FlowNodeUtil {

    private static final Logger LOGGER = Logger.getLogger(FlowNodeUtil.class.getName());

    private FlowNodeUtil() {
    }

    public abstract static class CacheExtensionPoint implements ExtensionPoint {
        public abstract Cache<String, RunExt> getRunCache();
    }

    // Used in testing where Jenkins is not running yet
    private static final List<CacheExtension> FALLBACK_CACHES = Arrays.asList(new CacheExtension());

    @Extension
    public static class CacheExtension extends CacheExtensionPoint {

        // Larger cache of run data, for completed runs, keyed by flowexecution url, useful for serving info
        // Actually can be used to serve Stage data too
        // Because the RunExt caps the total elements returned, and this is fully realized, this is the fastest way
        protected final Cache<String, RunExt> runData = CacheBuilder.newBuilder().maximumSize(1000).build();

        public Cache<String, RunExt> getRunCache() {
            return this.runData;
        }

        public static List<CacheExtension> all() {
            Jenkins myJenkins = Jenkins.getInstance();
            if ( myJenkins == null) {
                return FALLBACK_CACHES;
            } else {
                return myJenkins.getExtensionList(CacheExtension.class);
            }
        }
    }

    @CheckForNull
    public static RunExt getCachedRun(@Nonnull WorkflowRun run) {
        RunExt cachedRun = CacheExtension.all().get(0).getRunCache().getIfPresent(run.getExternalizableId());
        if (cachedRun != null) {
            return cachedRun;
        }
        return null;
    }

    public static void cacheRun(WorkflowRun run, RunExt runExt) {
        if (!run.isBuilding()) {
            CacheExtension.all().get(0).getRunCache().put(run.getExternalizableId(), runExt);
        }
    }

    public static boolean isNotPartOfRunningBuild(FlowExecution execution) {
        return (execution != null && execution.isComplete());
    }

    /** Find a node following this one */
    @CheckForNull
    public static FlowNode getNodeAfter(@Nonnull final FlowNode node) {
        if (node.isRunning()) {
            return null;
        }

        int iota = Integer.parseInt(node.getId());

        FlowExecution exec = node.getExecution();
        FlowNode nextNode = null;

        // Look for the next node or the one after it to see if it follows the current node, this can be much faster
        try {
             nextNode = exec.getNode(Integer.toString(iota + 1));
            if (nextNode != null && nextNode.getParents().contains(node)) {
                return nextNode;
            }
        } catch (IOException ioe) {
            try {
                nextNode = exec.getNode(Integer.toString(iota + 2));
                if (nextNode != null && nextNode.getParents().contains(node)) {
                    return nextNode;
                }
            } catch (IOException ioe2) {
                // Nope miss again, let's do the harder thing.
            }
        }

        // Find node after this one, scanning everything until this one
        final FlowNode after = new ForkScanner().findFirstMatch(node.getExecution().getCurrentHeads(), Collections.singletonList(node), new Predicate<FlowNode>() {
            public boolean apply(@Nonnull FlowNode f) {
                List<FlowNode> parents = f.getParents();
                return (parents != null && parents.contains(node));
            }
        });
        return after;
    }

    /**
     * Is the supplied node causing the workflow to pause at that point.
     * @param flowNode The node.
     * @return True if the node is causing the workflow to pause, otherwise false.
     */
    public static boolean isPauseNode(FlowNode flowNode) {
        return PauseAction.isPaused(flowNode);
    }

    // Enables us to get the status of a node without creating a bunch of objects
    public static StatusExt getStatus(FlowNode node) {
        boolean isExecuted = NotExecutedNodeAction.isExecuted(node);

        if (isExecuted) {
            ErrorAction errorAction = node.getError();
            return StatusExt.valueOf(errorAction);
        } else {
            return StatusExt.NOT_EXECUTED;
        }
    }

    /** This is used to cover an obscure case where a WorkflowJob is renamed BUT
     *  a previous WorkflowJob existed with cached execution data.
     *  Otherwise the previous job's cached data would be returned.
     **/
    @Extension
    public static class RenameHandler extends ItemListener {

        @Override
        public void onLocationChanged(Item item, String oldFullName, String newFullName) {
            // Better solution: scam through cache, find all instances where oldFullName matches
            // Replace with newFullName
            CacheExtension ext = CacheExtension.all().get(0);
            Cache<String, RunExt> rc = ext.getRunCache();

            if (item instanceof WorkflowJob) {
                RunList<WorkflowRun> runs = ((WorkflowJob) item).getBuilds().limit(JobExt.MAX_RUNS_PER_JOB+5);  // Add a few to help invalidate just-completed
                for (WorkflowRun r : runs) {
                    if (!r.isBuilding()) {
                        String path = oldFullName+"#"+r.getId();
                        RunExt cachedRun = rc.getIfPresent(path);
                        if (cachedRun != null) {
                            rc.invalidate(path);
                            rc.put(newFullName+"#"+r.getId(), cachedRun);
                        }
                    }
                }
            }
        }

        @Override
        public void onDeleted(Item item) {
            CacheExtension ext = CacheExtension.all().get(0);
            if (item instanceof WorkflowJob) {
                RunList<WorkflowRun> runs = ((WorkflowJob) item).getBuilds();
                for (WorkflowRun r : runs) {
                    ext.getRunCache().invalidate(r.getExternalizableId());
                }
            }
        }
    }

    /**
     * Simple debug utility for dumping a node list to sysout.
     * @param nodeList The list to dump.
     */
    public static void dumpNodes(List<FlowNode> nodeList) {
        System.out.println("------------------------------------------------------------------------------------------");
        for (FlowNode node : nodeList) {
            System.out.println("[" + node.getId() + "][" + TimingAction.getStartTime(node) + "] " + node.getDisplayName());
        }
        System.out.println("------------------------------------------------------------------------------------------");
    }
}
