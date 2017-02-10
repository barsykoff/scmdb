package com.onevizion.scmdb.facade;

import com.onevizion.scmdb.dao.SqlScriptDaoOra;
import com.onevizion.scmdb.vo.SqlScript;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.onevizion.scmdb.vo.ScriptType.COMMIT;
import static com.onevizion.scmdb.vo.ScriptType.ROLLBACK;

@Component
public class CheckoutFacade {
    @Resource
    private SqlScriptDaoOra dbScriptDaoOra;

    @Resource
    private DdlFacade ddlFacade;

    private final static String EXEC_FOLDER_NAME = "EXECUTE_ME";

    private final Logger logger = LoggerFactory.getLogger(getClass());

    public void createAllFromPath(File scriptDir) {
        Collection<File> files = FileUtils.listFiles(scriptDir, new String[]{"sql"}, false);
        dbScriptDaoOra.batchCreate(createScriptsFromFiles(files));
    }

    public List<SqlScript> getNewScripts(File scriptDir) {
        logger.debug("Searching new scripts in [{}]", scriptDir.getAbsolutePath());

        Map<String, SqlScript> savedScripts = dbScriptDaoOra.readMap();
        Collection<File> scriptFiles = FileUtils.listFiles(scriptDir, new String[]{"sql"}, false);
        List<SqlScript> scriptsInDir = createScriptsFromFiles(scriptFiles);

        scriptsInDir.stream()
                    .parallel()
                    .filter(this::isDevScript)
                    .forEach(script -> logger.info("Dev script [" + script.getName() + "] was ignored"));

        return scriptsInDir.stream()
                           .parallel()
                           .filter(script -> !savedScripts.containsKey(script.getName()))
                           .filter(script -> !isDevScript(script))
                           .collect(Collectors.toList());
    }

    private boolean isDevScript(SqlScript script) {
        String[] parts = script.getName().split("_");
        return parts.length <= 1 || !NumberUtils.isDigits(parts[0]);
    }


    @Transactional
    public List<SqlScript> getScriptsToExec(File scriptDir) {
        logger.debug("Searching new scripts in [{}]", scriptDir.getAbsolutePath());

        Map<String, SqlScript> dbScripts = dbScriptDaoOra.readMap();
        List<File> scriptFiles = (List<File>) FileUtils.listFiles(scriptDir, new String[]{"sql"}, false);
        List<SqlScript> scriptsInDir = createScriptsFromFiles(scriptFiles);

        for (SqlScript script : scriptsInDir) {
            if (dbScripts.containsKey(script.getName())) {
                SqlScript savedScript = dbScripts.get(script.getName());
                if (!script.getFileHash().equals(savedScript.getFileHash())) {
                    logger.warn("Script file was changed [{}]", script.getName());
                    savedScript.setFileHash(script.getFileHash());
                    dbScriptDaoOra.update(savedScript);
                }
            }
        }

        logger.debug("Searching deleted scripts in [{}]", scriptDir.getAbsolutePath());
        Collection<SqlScript> deletedScripts = CollectionUtils.subtract(dbScripts.values(), scriptsInDir);
        List<SqlScript> rollbacksToExec = new ArrayList<>();
        List<Long> deleteScriptIds = new ArrayList<>();
        for (SqlScript deletedScript : deletedScripts) {
            if (deletedScript.getType() == ROLLBACK) {
                deleteScriptIds.add(deletedScript.getId());
            } else if (deletedScript.getType() == COMMIT && dbScripts.containsKey(deletedScript.getRollbackName())) {
                rollbacksToExec.add(dbScripts.get(deletedScript.getRollbackName()));
                deleteScriptIds.add(deletedScript.getId());
            }
        }
        if (!deleteScriptIds.isEmpty()) {
            logger.debug("Deleting missed scripts form db");
            dbScriptDaoOra.deleteByIds(deleteScriptIds);
        }

        List<SqlScript> newScripts = scriptsInDir.stream()
                                                 .filter(script -> script.getType() == ROLLBACK)
                                                 .filter(script -> dbScripts.containsKey(script.getName()))
                                                 .collect(Collectors.toList());

        return copyScriptsToExecDir(scriptDir, newScripts, rollbacksToExec);
    }

    public List<SqlScript> copyScriptsToExecDir(File scriptDir, List<SqlScript> newScripts, List<SqlScript> rollbacks) {
        File execDir = createExecDir(scriptDir);

        for (SqlScript vo : rollbacks) {
            File f = new File(execDir.getAbsolutePath() + File.separator + vo.getName());
            try {
                logger.debug("Creating rollback script [{}]", f.getAbsolutePath());
                FileUtils.writeStringToFile(f, vo.getText());
                logger.info("Execute manually rollback script: [{}]", f.getAbsolutePath());
            } catch (IOException e) {
                logger.error("Can't create file [{}]", f.getAbsolutePath(), e);
                throw new RuntimeException(e);
            }
        }

        for (SqlScript script : newScripts) {
            if (script.getType() == ROLLBACK) {
                continue;
            }
            File srcFile = new File(scriptDir.getAbsolutePath() + File.separator + script.getName());
            File destFile = new File(execDir.getAbsolutePath() + File.separator + script.getName());
            try {
                logger.debug("Copying new script [{}]", destFile.getAbsolutePath());
                FileUtils.copyFile(srcFile, destFile);
                script.setFile(destFile);
            } catch (IOException e) {
                logger.error("Can't copy file [{}]", srcFile.getAbsolutePath(), e);
                throw new RuntimeException(e);
            }
        }

        return newScripts;
    }

    public void genDdl(File scriptDir, Collection<SqlScript> newScripts) {
        List<SqlScript> newCommitScripts = new ArrayList<>();
        for (SqlScript vo : newScripts) {
            if (vo.getType() == COMMIT) {
                newCommitScripts.add(vo);
            }
        }
        ddlFacade.generateDdl(newCommitScripts, scriptDir);
    }

    public boolean isFirstRun() {
        return dbScriptDaoOra.readCount().equals(0L);
    }

    private File createExecDir(File scriptDir) {
        File execDir = new File(scriptDir.getAbsolutePath() + File.separator + EXEC_FOLDER_NAME);
        if (execDir.exists()) {
            try {
                FileUtils.deleteDirectory(execDir);
            } catch (IOException e) {
                logger.error("Can't delete directory by path {" + execDir.getAbsolutePath() + "}", e);
            }
        }
        return execDir;
    }

    private List<SqlScript> createScriptsFromFiles(Collection<File> files) {
        return files.stream()
                    .map(SqlScript::create)
                    .collect(Collectors.toList());
    }
}