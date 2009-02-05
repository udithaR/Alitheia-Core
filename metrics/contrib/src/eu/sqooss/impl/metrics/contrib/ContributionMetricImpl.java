/*
 * This file is part of the Alitheia system, developed by the SQO-OSS
 * consortium as part of the IST FP6 SQO-OSS project, number 033331.
 *
 * Copyright 2008 - Organization for Free and Open Source Software,  *                Athens, Greece.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *
 *     * Redistributions in binary form must reproduce the above
 *       copyright notice, this list of conditions and the following
 *       disclaimer in the documentation and/or other materials provided
 *       with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 */

package eu.sqooss.impl.metrics.contrib;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.osgi.framework.BundleContext;

import eu.sqooss.core.AlitheiaCore;
import eu.sqooss.impl.metrics.contrib.ContributionActions.ActionCategory;
import eu.sqooss.impl.metrics.contrib.ContributionActions.ActionType;
import eu.sqooss.metrics.contrib.ContributionMetric;
import eu.sqooss.metrics.contrib.db.ContribAction;
import eu.sqooss.metrics.contrib.db.ContribActionType;
import eu.sqooss.metrics.contrib.db.ContribActionWeight;
import eu.sqooss.metrics.contrib.db.ContribLinesPerDevPerFile;
import eu.sqooss.service.abstractmetric.AbstractMetric;
import eu.sqooss.service.abstractmetric.AlitheiaPlugin;
import eu.sqooss.service.abstractmetric.AlreadyProcessingException;
import eu.sqooss.service.abstractmetric.MetricMismatchException;
import eu.sqooss.service.abstractmetric.Result;
import eu.sqooss.service.abstractmetric.ResultEntry;
import eu.sqooss.service.db.Bug;
import eu.sqooss.service.db.DAObject;
import eu.sqooss.service.db.DBService;
import eu.sqooss.service.db.Developer;
import eu.sqooss.service.db.MailMessage;
import eu.sqooss.service.db.MailingList;
import eu.sqooss.service.db.MailingListThread;
import eu.sqooss.service.db.Metric;
import eu.sqooss.service.db.MetricType;
import eu.sqooss.service.db.PluginConfiguration;
import eu.sqooss.service.db.ProjectFile;
import eu.sqooss.service.db.ProjectVersion;
import eu.sqooss.service.db.StoredProject;
import eu.sqooss.service.fds.FileTypeMatcher;
import eu.sqooss.service.fds.FileTypeMatcher.FileType;
import eu.sqooss.service.pa.PluginInfo;
import eu.sqooss.service.tds.AnnotatedLine;
import eu.sqooss.service.tds.Diff;
import eu.sqooss.service.tds.DiffChunk;
import eu.sqooss.service.tds.InvalidAccessorException;
import eu.sqooss.service.tds.InvalidProjectRevisionException;
import eu.sqooss.service.tds.InvalidRepositoryException;
import eu.sqooss.service.tds.SCMAccessor;
import eu.sqooss.service.tds.TDSService;

public class ContributionMetricImpl extends AbstractMetric implements
        ContributionMetric {

    private Object lockObject = new Object();
    private static long processedObjects = 0;
    
    /** Number of files after which a commit is considered too big */
    public static final String CONFIG_CMF_THRES = "CMF_threshold";
    
    /** Number of project resources processed prior to each weight update*/
    public static final String CONFIG_WEIGHT_UPDATE_VERSIONS = "Weights_Update_Interval";
    public static final int DEFAULT_WEIGHT_UPDATE_INTERVAL = 150;
    private int weightUpdateInterval;
    
    /** Name of the measurement*/
    public static final String METRIC_CONTRIB = "CONTRIB";
    
    
    public ContributionMetricImpl(BundleContext bc) {
        super(bc);
        super.addActivationType(ProjectVersion.class);
        super.addActivationType(Developer.class);
        super.addActivationType(MailingListThread.class);
        super.addActivationType(Bug.class);
        
        super.addMetricActivationType("CONTRIB", Developer.class);
        
        super.addDependency("Wc.loc");   
    }
    
    public boolean install() {
    	 boolean result = super.install();
         if (result) {
             result &= super.addSupportedMetrics(
                     "Developer Contribution Metric",
                     METRIC_CONTRIB,
                     MetricType.Type.PROJECT_WIDE);
         
             addConfigEntry(CONFIG_CMF_THRES, 
                 "5" , 
                 "Number of committed files above which the developer is " +
                 "penalized", 
                 PluginInfo.ConfigurationType.INTEGER);
             addConfigEntry(CONFIG_WEIGHT_UPDATE_VERSIONS, 
                 String.valueOf(DEFAULT_WEIGHT_UPDATE_INTERVAL) , 
                 "Number of revisions between weight updates", 
                 PluginInfo.ConfigurationType.INTEGER);
         }
         return result;
    }
    
    public boolean remove() {
        boolean result = true;
        
        String[] tables = {"ContribActionWeight", 
                           "ContribAction",
                           "ContribActionType"};
        
        for (String tablename : tables) {
            result &= db.deleteRecords((List<DAObject>) db.doHQL(
                    "from " + tablename));
        }
        
        result &= super.remove();
        return result;
    }
    
    /**{@inheritDoc}*/
    public boolean cleanup(DAObject sp) {
        boolean result = true;
        
        if (!(sp instanceof StoredProject)) {
            log.warn("We only support cleaning up per stored project for now");
            return false;
        }
        result &= cleanupResource (((StoredProject)sp).getProjectVersions(), 
                ActionCategory.C);
        result &= cleanupResource(((StoredProject)sp).getBugs(), 
                ActionCategory.B);
        
        Set<MailingList> mlists = ((StoredProject) sp).getMailingLists();
        for (MailingList ml : mlists) {
            result &= cleanupResource(ml.getMessages(), ActionCategory.M);            
        }
       
        return result;
    }

    private boolean cleanupResource (Collection<? extends DAObject> c, 
            ActionCategory ac) {
        
        Map<String,Object> params = new HashMap<String,Object>();
        boolean result = false;
        
        for(DAObject o : c) {
            params.put("changedResourceId", o.getId());
            params.put("actionCategory", ac.toString());
            List<ContribAction> pas = 
                db.findObjectsByProperties(ContribAction.class, params);
            if (!pas.isEmpty()) {
                for (ContribAction pa : pas) {
                    result &= db.deleteRecord(pa);
                }
            }
            params.clear();
        }
        return result;
    }

    /*
     * The following methods are dummy implementations that just
     * check if a result has been calculated for the provided
     * DAO or not. 
     */
    public List<ResultEntry> getResult(ProjectVersion a, Metric m) {
       return checkResult(a, ActionCategory.C, m);
    }
    
    public List<ResultEntry> getResult(MailingListThread mm, Metric m) {
        return checkResult(mm, ActionCategory.M, m);
    }
    
    public List<ResultEntry> getResult(Bug b, Metric m) {
        return checkResult(b, ActionCategory.B, m);
    }
    
    public List<ResultEntry> getResult(MailMessage mm, Metric m) {
        return checkResult(mm, ActionCategory.M, m);
    }
    
    private List<ResultEntry> checkResult(DAObject o, ActionCategory ac, 
            Metric m) {
        ArrayList<ResultEntry> res = new ArrayList<ResultEntry>();
        
        if (getResult(o) == null)
            return null;

        //Return a fixed result to indicate successful run on this 
        //project resource
        res.add(new ResultEntry(1, ResultEntry.MIME_TYPE_TYPE_INTEGER, 
                m.getMnemonic()));
        return res;
    }

    private ContribAction getResult(DAObject o) {
        String paramChResource = "paramChResource";
        String paramActionCategory = "paramActionCategory";
        
        String query = "select ca " +
            "from ContribAction ca, ContribActionType cat " +
            " where ca.contribActionType = cat " +
            " and cat.actionCategory = :" + paramActionCategory +
            " and ca.changedResourceId = :" + paramChResource ;
        
        Map<String,Object> parameters = new HashMap<String,Object>();
        parameters.put(paramChResource, o.getId());
        
        if (o instanceof MailingListThread || o instanceof MailMessage) {
            parameters.put(paramActionCategory, ActionCategory.M.toString());
        } else if (o instanceof ProjectVersion) {
            parameters.put(paramActionCategory, ActionCategory.C.toString());
        } else if (o instanceof Bug) {
            parameters.put(paramActionCategory, ActionCategory.B.toString());
        } 

        List<ContribAction> lp = (List<ContribAction>) db.doHQL(query, parameters, 1);
    
        if (lp == null || lp.isEmpty()) {
            return null;
        }
        
        return lp.get(0);
    }

    /*
     * This plug-in's result is only returned per developer. 
     */
    public List<ResultEntry> getResult(Developer a, Metric m) {
        ArrayList<ResultEntry> results = new ArrayList<ResultEntry>();
        ContribActionWeight weight;
        double value = 0;

        ActionCategory[] actionCategories = ActionCategory.values();

        for (int i = 0; i < actionCategories.length; i++) {
            weight = ContribActionWeight.getWeight(actionCategories[i]);

            if (weight != null) {
                value = value + weight.getWeight() * 
                    getResultPerActionCategory(a, actionCategories[i]);
            }
        }

        ResultEntry entry = new ResultEntry(value,
                ResultEntry.MIME_TYPE_TYPE_DOUBLE, m.getMnemonic());
        results.add(entry);
        return results;
    }

    public void run(MailMessage mm) throws AlreadyProcessingException {}
    public void run(Developer v) throws AlreadyProcessingException {}
    public void run(Bug b) throws AlreadyProcessingException {}

    public void run(MailingListThread t) throws AlreadyProcessingException {
        Metric contrib = Metric.getMetricByMnemonic(METRIC_CONTRIB);
        List<MailMessage> emails = t.getMessagesByArrivalOrder();
        MailMessage lastProcessed = null;
        
        //Find the last email from this thread's collection of emails
        //that has been processed in a previous invocation. Avoid 
        //scanning threads with just one email.
        for (int i = emails.size() - 1; i > 0; i--) { 
            //Find first email whose contrib action is not null
            ContribAction old = getResult(emails.get(i));
            if (old != null) {
                lastProcessed = DAObject.loadDAObyId(
                        old.getChangedResourceId(), MailMessage.class);
            }
        }
        
        for (MailMessage mm : emails) {
            ContribAction ca = getResult(mm);
            if (ca!= null) {
                //This mail has been processed again, check if 
                //email that closes the thread has been updated
                if (lastProcessed != null && mm.equals(lastProcessed)) {
                    ContribAction oldCa = ContribAction.getContribAction(
                            lastProcessed.getSender(), lastProcessed.getId(),
                            ContribActionType.getContribActionType(
                                    ActionType.MCT, true));
                    if (oldCa != null) {
                        oldCa.setTotal(oldCa.getTotal() - 1);
                    }
                }
                continue;
            }
                        
            if (mm.getThreadEntry().getParent() == null) {
                //New thread
                updateField(mm, mm.getSender(), ActionType.MST, true, 1);
            } else{
                if (mm.getThreadEntry().getDepth() == 1) {
                  //First reply to a thread
                    MailMessage firstMessage = t.getMessagesAtLevel(1).get(0);
                    if (firstMessage.equals(mm))
                        updateField(mm, mm.getSender(), ActionType.MFR, true, 1);
                }
                
                if (mm.equals(emails.get(emails.size() - 1))) {
                    //Mail that closes a thread
                    updateField(mm, mm.getSender(), ActionType.MCT, true, 1);
                }
            }
            
            updateField(mm, mm.getSender(), ActionType.MSE, true, 1);
        }
        
        //Update the category weights, if necessary
        synchronized(lockObject) {
            updateWeights();      
        }
        
        markEvaluation(contrib, t.getList().getStoredProject());
    }
    
    public void run(ProjectVersion pv) throws AlreadyProcessingException {
        /* Read config options in advance*/        
        FileTypeMatcher.FileType fType;
        Developer dev = pv.getCommitter();
        Set<ProjectFile> projectFiles = pv.getVersionFiles();
        List<Metric> locMetric = new ArrayList<Metric>();
        AlitheiaPlugin plugin = AlitheiaCore.getInstance().getPluginAdmin().getImplementingPlugin("Wc.loc");
        
        if (plugin != null) {
            locMetric = plugin.getSupportedMetrics();
        } else {
            err("Could not find the WC plugin", pv);
            return;
        }
        
        int numFilesThreshold;
        int updateThreshold = getWeightUpdateThreshold(); 
        
        PluginConfiguration config = getConfigurationOption(
                ContributionMetricImpl.CONFIG_CMF_THRES);
        
        if (config == null || 
                Integer.parseInt(config.getValue()) <= 0) {
            err("Plug-in configuration option " 
            		+ ContributionMetricImpl.CONFIG_CMF_THRES 
                    + " not found", pv);
            return; 
        } else {
            numFilesThreshold = Integer.parseInt(config.getValue());
        }    
        
        Pattern bugNumberLabel = Pattern.compile("\\A.*(pr:|bug:).*\\Z",
                Pattern.CASE_INSENSITIVE | Pattern.MULTILINE | Pattern.DOTALL);

        Pattern pHatLabel = Pattern.compile(
                "\\A.*(ph:|pointy hat|p?hat:).*\\Z", Pattern.CASE_INSENSITIVE
                        | Pattern.MULTILINE | Pattern.DOTALL);
        Matcher m;

        //Commit message is empty
        if (pv.getCommitMsg().length() == 0) {
            updateField(pv, dev, ActionType.CEC, false, 1);
        } else {
            //Commit contains a bug report number
            m = bugNumberLabel.matcher(pv.getCommitMsg());
            if (m.matches()) {
                updateField(pv, dev, ActionType.CBN, true, 1);
            }
            //Commit awards a pointy hat
            m = pHatLabel.matcher(pv.getCommitMsg());
            if (m.matches()) {
                updateField(pv, dev, ActionType.CPH, true, 1);
            }
        }
        
        //Commit more files in a commit than the provided threshold
        if (projectFiles.size() > numFilesThreshold) {
            updateField(pv, dev, ActionType.CMF, false, 1);
        }

        FileTypeMatcher ftm = FileTypeMatcher.getInstance();
        Iterator<ProjectFile> i = projectFiles.iterator();
        
        while (i.hasNext()) {
            ProjectFile pf = i.next();
            
            if (pf.getIsDirectory()) {
                //New directory added
                if (pf.isAdded()) {
                    updateField(pv, dev, ActionType.CND, true, 1);
                }
                continue;
            }
            
            fType = ftm.getFileType(pf.getFileName());
            
            if (pf.getCopyFrom() != null) {
                debug("Ignoring copied file" + pf, pf.getProjectVersion());
                continue;
            }
            
            //Commit of a source file
            if (ftm.isTextType(pf.getFileName())) {
                //Source file changed, calc number of lines commited
                try {
                    if (pf.isDeleted()) {
                    	int locPrev = getLOCResult(pf.getPreviousFileVersion(), plugin, locMetric);
                        updateField(pv, dev, ActionType.TLR, true, locPrev);
                    } 
                    //Source file just added
                    else if (pf.isAdded()) {
                        updateField(pv, dev, ActionType.CNS, true, 1);
                        updateField(pv, dev, ActionType.TLA, true, 
                        		getLOCResult(pf, plugin, locMetric));
                    } else {
                        //Existing file, get lines of previous version
                        ProjectFile prevFile = pf.getPreviousFileVersion();
                        SCMAccessor scm = AlitheiaCore.getInstance().getTDSService().getAccessor(pv.getProject().getId()).getSCMAccessor();
                        Diff d = scm.getDiff(pf.getFileName(), 
                        		scm.newRevision(prevFile.getProjectVersion().getRevisionId()),
                        		scm.newRevision(pf.getProjectVersion().getRevisionId()));
                        Map<String, List<DiffChunk>> diff = d.getDiffChunks();
                        List<DiffChunk> chunks = diff.get(pf.getFileName());
                        int added = 0, removed = 0;
                        
                        for (DiffChunk chunk : chunks) {
                        	String theDiff = chunk.getChunk();
                        	BufferedReader r = new BufferedReader(new StringReader(theDiff));
                    		String line;
                    		while ((line = r.readLine()) != null) {
                    			if (line.startsWith("+")) 
                    				added ++;
                    			if (line.startsWith("-"))
                    				removed++;
                    		}
                        }
                        
                        if (added != 0 && removed != 0 ) {
                        	updateField(pv, dev, ActionType.TLM, true, Math.min(added, removed));
                        }
                        
                        if (added > removed) {
                        	updateField(pv, dev, ActionType.TLA, true, Math.abs(added - removed));
                        }
                        else { 
                        	updateField(pv, dev, ActionType.TLR, true, Math.abs(added - removed));
                        }
                    }
                } catch (MetricMismatchException e) {
					e.printStackTrace();
				} catch (InvalidAccessorException e) {
					e.printStackTrace();
				} catch (InvalidProjectRevisionException e) {
					e.printStackTrace();
				} catch (InvalidRepositoryException e) {
					e.printStackTrace();
				} catch (FileNotFoundException e) {
					e.printStackTrace();
				} catch (Exception e) {
					e.printStackTrace();
				} 
            }
            
            if (pf.isAdded()) {
            	if (fType == FileTypeMatcher.FileType.SRC) {
					// Commit of a new source file: +
					updateField(pv, dev, ActionType.CNS, true, 1);
				}

				if (fType == FileTypeMatcher.FileType.BIN) {
					// Commit of a binary file: -
					updateField(pv, dev, ActionType.CBF, false, 1);
				}

				if (fType == FileTypeMatcher.FileType.DOC) {
					// Commit of a documentation file: +
					updateField(pv, dev, ActionType.CDF, true, 1);
				}

				if (fType == FileTypeMatcher.FileType.TRANS) {
					// Commit of a translation file: +
					updateField(pv, dev, ActionType.CTF, true, 1);
				}
            }
        }

        markEvaluation(Metric.getMetricByMnemonic("CONTRIB"), pv);
    }

    private int getLOCResult(ProjectFile pf, AlitheiaPlugin plugin, 
            List<Metric> locMetric) 
        throws MetricMismatchException, AlreadyProcessingException, Exception {
      //Get lines of current version of the file from the wc metric
        Result r = plugin.getResult(pf, locMetric);
        if (r != null && r.hasNext()) {
            return r.getRow(0).get(0).getInteger();
        }
        else { 
            warn("Plugin <" + plugin.getName() + "> did" +
                    " not return a result for file " + pf, 
                    pf.getProjectVersion() );
            return 0;
        }
    }
    
    /**
     * Get result per developer and per category
     */
    private double getResultPerActionCategory(Developer d, ActionCategory ac) {
        ContribActionWeight weight;
        long totalActions;
        double value = 0;

        for (ActionType at : ActionType.getActionTypes(ac)) {
            weight = ContribActionWeight.getWeight(at);
            
            if (weight == null) {
                continue;
            }
            
            ContribActionType cat = 
                ContribActionType.getContribActionType(at, null);
                
            totalActions = 
                ContribAction.getTotalActionsPerTypePerDeveloper(at, d);

            if(totalActions != 0){
                if (cat.getIsPositive())
                    value += weight.getWeight() * totalActions;
                else
                    value -= weight.getWeight() * totalActions;
            }
        }
        return value;
    }
 
    private void updateField(DAObject o, Developer dev, 
            ActionType actionType, boolean isPositive, int value) {
        DBService db = AlitheiaCore.getInstance().getDBService();
        ContribActionType at = ContribActionType.getContribActionType(actionType,
                isPositive);
        
        if (at == null) {
            db.rollbackDBSession();
            return;
        }

        ContribAction a = ContribAction.getContribAction(dev, o.getId(), at);

        if (a == null) {
            a = new ContribAction();
            a.setDeveloper(dev);
            a.setChangedResourceId(o.getId());
            a.setContribActionType(at);
            a.setTotal(value);
            db.addRecord(a);
        } else {
            a.setTotal(a.getTotal() + value);
        }
    }
    
    private void updateWeights() {
        
      /*  long distinctVersions = 0;
        List<?> versions = db.doHQL("select " +
        "count(distinct changedResourceId) from ContribAction");

        if (versions == null || 
                versions.size() == 0 || 
                versions.get(0) == null) {
            return;
        }
        
        distinctVersions = (Integer.parseInt(versions.get(0).toString())) ;
        
        //Should the weights be updated?
        if (distinctVersions % getWeightUpdateThreshold() != 0){
           return;
        }
        */
        processedObjects ++;
        if (processedObjects % getWeightUpdateThreshold() != 0 ) {
            return;
        }
        
        ActionCategory[] actionCategories = ActionCategory.values();

        long totalActions = ContribAction.getTotalActions();
        long totalActionsPerCategory;
        long totalActionsPerType;
        
        if (totalActions <= 0) {
            return;
        }
        
        for (int i = 0; i < actionCategories.length; i++) {
            //update action category weight
            totalActionsPerCategory = 
                ContribAction.getTotalActionsPerCategory(actionCategories[i]);
                
            if (totalActionsPerCategory <= 0) {
                continue;
            }
            
            updateActionCategoryWeight(actionCategories[i],
                    totalActionsPerCategory, totalActions);

            // update action types weights
            ArrayList<ActionType> actionTypes = 
                ActionType.getActionTypes(actionCategories[i]);

            for (int j = 0; j < actionTypes.size(); j++) {
                totalActionsPerType = 
                    ContribAction.getTotalActionsPerType(actionTypes.get(j));
                updateActionTypeWeight(actionTypes.get(j),totalActionsPerType, 
                        totalActionsPerCategory);
            }
        }
    }
    
    private void updateActionTypeWeight(ActionType actionType, 
            long totalActionsPerType, long totalActionsPerCategory) {
        
        DBService db = AlitheiaCore.getInstance().getDBService();
        double weight = (double)(100 * totalActionsPerType) / 
            (double)totalActionsPerCategory;

        ContribActionWeight a = ContribActionWeight.getWeight(actionType);
       
        if (a == null) {
            a = new ContribActionWeight();
            a.setType(actionType);
            a.setWeight(weight);
            db.addRecord(a);
        } else {
            a.setWeight(weight);
        }
    }
    
    private void updateActionCategoryWeight(ActionCategory actionCategory, 
            long totalActionsPerCategory, long totalActions){
        DBService db = AlitheiaCore.getInstance().getDBService();
        double weight = (double)(100 * totalActionsPerCategory) / 
            (double)totalActions;

        ContribActionWeight a = ContribActionWeight.getWeight(actionCategory);

        if (a == null) { //No weight calculated for this action yet
            a = new ContribActionWeight();
            a.setCategory(actionCategory);
            a.setWeight(weight);
            db.addRecord(a);
        } else {
            a.setWeight(weight);
        }
    }
    
    private int abs (int value){
        if (value < 0) 
            return -1 * value;
        else
            return value;
    }
    
    private int getWeightUpdateThreshold() {
        if (weightUpdateInterval == 0) {
        PluginConfiguration config = getConfigurationOption(CONFIG_WEIGHT_UPDATE_VERSIONS);

            if (config == null || Integer.parseInt(config.getValue()) <= 0) {
                log.warn("Plug-in configuration option "
                        + CONFIG_WEIGHT_UPDATE_VERSIONS + " not found,"
                        + "setting to default value: " 
                        + DEFAULT_WEIGHT_UPDATE_INTERVAL);
                weightUpdateInterval = DEFAULT_WEIGHT_UPDATE_INTERVAL;
            } else {
                weightUpdateInterval = Integer.parseInt(config.getValue());
            }
        }
        return weightUpdateInterval;
    }
    
    private void err(String msg, DAObject o) {
    	log.error("Contrib (" + o.getClass() + "): Object: " + o.toString() 
    			+ " Error:"+ msg);
    }
    
    private void warn(String msg, DAObject o) {
    	log.error("Contrib (" + o.getClass() + "): Object: " + o.toString() 
    			+ " Warning:" + msg);
    }
    
    private void info(String msg, DAObject o) {
    	log.error("Contrib (" + o.getClass() + "): Object: " + o.toString() 
    			+ " Info:" + msg);
    }
    
    private void debug(String msg, DAObject o) {
    	log.error("Contrib (" + o.getClass() + "): Object: " + o.toString() 
    			+ " Debug:" + msg);
    }
}

// vi: ai nosi sw=4 ts=4 expandtab
