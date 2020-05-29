package com.elastic.support.monitoring;

import com.elastic.support.Constants;
import com.elastic.support.rest.RestClient;
import com.elastic.support.rest.RestResult;
import com.elastic.support.util.ArchiveEntryProcessor;
import com.elastic.support.util.JsonYamlUtils;
import com.elastic.support.util.SystemProperties;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Vector;

public class MonitoringImportProcessor{

    private static final Logger logger = LogManager.getLogger(MonitoringImportProcessor.class);

    RestClient client;
    MonitoringImportConfig config;
    MonitoringImportInputs inputs;
    String newClusterName;
    String index;
    boolean updateClusterName = false;

    public MonitoringImportProcessor(MonitoringImportConfig config, MonitoringImportInputs inputs, RestClient client) {
        this.config = config;
        this.inputs = inputs;
        this.client = client;

        // Check for overrides
        if(StringUtils.isNotEmpty(inputs.clusterName)){
            this.newClusterName = inputs.clusterName;
            updateClusterName = true;
        }

        index = Constants.MONITORING_PREFIX   + inputs.indexName;

    }

    public void exec(Vector<File> files){

        try {
            for(File file: files){
                if (file.isDirectory() || file.getName().contains(".log")){
                    continue;
                }
                logger.info("Processing: {}", file.getName());
                process(file);
            }
        } catch (Exception e) {
            logger.error(e);
        }
    }

    public void process(File file) {

        logger.info(Constants.CONSOLE, "Processing: {}", file.getName());
        long eventsWritten = 0;

        try(InputStream instream = new FileInputStream(file)) {
            BufferedReader br = new BufferedReader(new InputStreamReader(instream));
            StringBuilder batchBuilder = new StringBuilder();
            String contents;
            int batch = 0;
            Map<String, Map> inputIndex = new LinkedHashMap();
            Map<String, String> inputIndexField = new LinkedHashMap<>();
            inputIndexField.put("_index", index);
            inputIndex.put("index", inputIndexField);
            String indexLine = JsonYamlUtils.mapper.writeValueAsString(inputIndex);

            try {
                while ( ( contents = br.readLine()) != null){
                    // If clustername is present and they changed it, update
                    ObjectNode sourceObject = JsonYamlUtils.mapper.readValue(contents, ObjectNode.class);
                    String clusterName = sourceObject.path("cluster_name").asText();

                    if(updateClusterName && StringUtils.isNotEmpty(clusterName)){
                        sourceObject.put("cluster_name", newClusterName);
                    }

                    String altClusterName = sourceObject.path("cluster_settings").path("cluster").path("metadata").path("display_name").asText();
                    if(StringUtils.isNotEmpty(altClusterName)){
                        sourceObject.with("cluster_settings").with("cluster").with("metadata").put("display_name", newClusterName);
                    }

                    String sourceLine= JsonYamlUtils.mapper.writeValueAsString(sourceObject);

                    batchBuilder.append(indexLine + "\n");
                    batchBuilder.append(sourceLine + "\n");

                    // See if we need to
                    if(batch >= config.bulkSize){
                        logger.info(Constants.CONSOLE,  "Indexing document batch {} to {}",  eventsWritten, eventsWritten+batch);

                        long docsWritten = writeBatch(batchBuilder.toString(), batch);
                        eventsWritten += docsWritten;
                        batch = 0;
                        batchBuilder.setLength(0);
                    }
                    else {
                        batch++;
                    }
                }

                // if there's anything left do the cleanup
                if(batch > 0){
                    logger.info(Constants.CONSOLE,  "Indexing document batch {} to {}",  eventsWritten, eventsWritten+batch);
                    long docsWritten = writeBatch(batchBuilder.toString(), batch);
                    eventsWritten  += docsWritten;
                }

            } catch (Throwable t) {
                // If something goes wrong just log it and keep boing.
                logger.error(Constants.CONSOLE,"Error processing JSON event for {}.", t);
            }
        } catch (Throwable t) {
            logger.error(Constants.CONSOLE,"Error processing entry - stream related error,", t);
        }
        finally {
            logger.info(Constants.CONSOLE,"{} events written from {}", eventsWritten, file.getName());
        }

    }

    public void init(ZipFile zipFile) {
        // Nothing to do here;;
    }

    private long writeBatch(String query, int size){
        RestResult res = new RestResult( client.execPost("_bulk", query), "_bulk");
        if( res.getStatus() != 200){
            logger.error(Constants.CONSOLE,"Batch update had errors: {}  {}", res.getStatus(), res.getReason());
            logger.error(Constants.CONSOLE,Constants.CHECK_LOG);
            logger.error(res.toString());
            return 0;
        }
        else {
            return size;
        }
    }

}
