package com.thinkbiganalytics.nifi.provenance.v2.writer;

import com.thinkbiganalytics.activemq.ObjectMapperSerializer;
import com.thinkbiganalytics.activemq.SendJmsMessage;
import com.thinkbiganalytics.nifi.activemq.ProvenanceEventReceiverDatabaseWriter;
import com.thinkbiganalytics.nifi.activemq.Queues;
import com.thinkbiganalytics.nifi.provenance.model.ProvenanceEventRecordDTO;
import com.thinkbiganalytics.nifi.provenance.model.ProvenanceEventRecordDTOHolder;
import com.thinkbiganalytics.nifi.provenance.model.stats.AggregatedFeedProcessorStatisticsHolder;

import org.h2.tools.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.stereotype.Component;

import java.util.List;

import javax.annotation.PostConstruct;

/**
 * Created by sr186054 on 3/3/16.
 */
@Component
public class ProvenanceEventActiveMqWriter {

    private static final Logger logger = LoggerFactory.getLogger(ProvenanceEventActiveMqWriter.class);
    private boolean jmsUnavailable;
    private EmbeddedDatabase db;
    private Server h2Console;


    @Autowired
    private SendJmsMessage sendJmsMessage;

    @Autowired
    ObjectMapperSerializer objectMapperSerializer;

    @Autowired
    ProvenanceEventReceiverDatabaseWriter databaseWriter;


    @Value("${thinkbig.provenance.h2.databaseName}")
    private String h2DatabaseName;

    @Value("${thinkbig.provenance.h2.showWebConsole:false}")
    private boolean startH2WebConsole;

    @Value("${thinkbig.provenance.h2.webConsolePort:8082}")
    private String h2WebConsolePort;

    public ProvenanceEventActiveMqWriter() {

    }

    @PostConstruct
    public void postConstruct() {

    }


    /**
     * Write to the provenance-event queue This is used for important events such as Start, Fail, End
     */
    public void writeEvents(ProvenanceEventRecordDTOHolder events) {
        logger.info("SENDING {} events to {} ", events, Queues.PROVENANCE_EVENT_QUEUE);
        sendJmsMessage.sendSerializedObjectToQueue(Queues.PROVENANCE_EVENT_QUEUE, events);
    }

    /**
     * Write out stats to JMS
     * @param stats
     */
    public void writeStats(AggregatedFeedProcessorStatisticsHolder stats) {
        try {
            logger.info("SENDING AGGREGATED STAT to JMS {} ", stats);
            sendJmsMessage.sendSerializedObjectToQueue(Queues.PROVENANCE_EVENT_STATS_QUEUE, stats);

        } catch (Exception e) {
            logger.error("JMS Error has occurred sending stats. Temporary queue has been disabled in this current version.", e);
        }
    }

    /***
     * Write the batch events to JMS
     * @param events
     */
    public void writeBatchEvents(ProvenanceEventRecordDTOHolder events) {
        try {
            logger.info("SENDING Events to JMS {} ", events);

            try {
                if (jmsUnavailable) {
                    persistEventToTemporaryTable(events);
                } else {
                    logger.info("Processing the JMS message as normal");
                    sendJmsMessage.sendSerializedObjectToQueue(Queues.FEED_MANAGER_QUEUE, events);
                }
            } catch (Exception e) {
                logger.error("JMS Error has occurred. Enable temporary queue", e);
                jmsUnavailable = true;
                try {
                    initializeTemporaryDatabase();
                    databaseWriter.writeEvent(events);
                } catch (Exception dwe) {
                    logger.error("Error writing the temporary provenance event to the database", dwe);
                }
            }

        } catch (Exception e) {
            logger.error("JMS Error has occurred sending events. Temporary queue has been disabled in this current version.", e);
        }
    }

   /* @Deprecated
    public Long writeEvent(ProvenanceEventRecordDTO event) {
        logger.debug(
            "SENDING JMS PROVENANCE_EVENT for EVENT_ID: " + event.getEventId() + ", COMPONENT_ID: " + event.getComponentId()
            + ", COMPONENT_TYPE: " + event.getComponentType() + ", EVENT_TYPE: " + event.getEventType());
        try {
            if (jmsUnavailable) {
                persistEventToTemporaryTable(event);
            } else {
                logger.info("Processing the JMS message as normal");
                sendJmsMessage.sendSerializedObjectToQueue(Queues.FEED_MANAGER_QUEUE, event);
            }
        } catch (Exception e) {
            logger.error("JMS Error has occurred. Enable temporary queue", e);
            jmsUnavailable = true;
            try {
                initializeTemporaryDatabase();
                databaseWriter.writeEvent(event);
            } catch (Exception dwe) {
                logger.error("Error writing the temporary provenance event to the database", dwe);
            }
        }
        return event.getEventId();
    }
*/

    //public Long writeEvent(ProvenanceEventRecord event) {
    //   ProvenanceEventRecordDTO dto = ProvenanceEventRecordConverter.convert(event);
    //   return writeEvent(dto);
    // }

    private void initializeTemporaryDatabase() throws Exception {
        logger.info("Starting H2 database. The database name is:  " + h2DatabaseName);
        db = new EmbeddedDatabaseBuilder()
            .generateUniqueName(false)
            .setType(EmbeddedDatabaseType.H2)
            .setScriptEncoding("UTF-8")
            .ignoreFailedDrops(true)
            .setName(h2DatabaseName)
            .build();
        if (startH2WebConsole) {
            logger.info("Starting the H2 web console");
            h2Console = Server.createWebServer("-web", "-webAllowOthers", "-webDaemon", "-webPort", h2WebConsolePort);
            h2Console.start();
        }
        logger.info("Started H2 database");

        databaseWriter.createTables();
    }

    private void persistEventToTemporaryTable(ProvenanceEventRecordDTOHolder holder) throws Exception {
        holder.getEvents().stream().forEach(e -> {
            try {
                persistEventToTemporaryTable(e);
            } catch (Exception e1) {
                logger.error("JMS is down and an error occurred writing to the temporary H2 Datrabase for event {}, {}", e, e1.getMessage(), e1);
            }
        });
    }

    private void persistEventToTemporaryTable(ProvenanceEventRecordDTO dto) throws Exception {
        boolean isJmsRunningNow = sendJmsMessage.testJmsIsRunning();
        if (isJmsRunningNow) {
            logger.info("JMS is running now. Processing the cached messages");
            // catch up on the cached messages then send the last message
            List<ProvenanceEventRecordDTO> eventsFromDatabase = databaseWriter.getEvents();
            for (ProvenanceEventRecordDTO eventDTO : eventsFromDatabase) {
                sendJmsMessage.sendObjectToQueue(Queues.FEED_MANAGER_QUEUE, eventDTO);
            }
            databaseWriter.clearEvents();
            sendJmsMessage.sendSerializedObjectToQueue(Queues.FEED_MANAGER_QUEUE, dto);

            shutdownTemporaryDatabaseAndResumeJms();
        } else {
            logger.info("JMS server still down so caching the new message");
            databaseWriter.writeEvent(dto);
        }
    }

    private void shutdownTemporaryDatabaseAndResumeJms() {
        jmsUnavailable = false;
        if (h2Console != null) {
            h2Console.stop();
        }
        db.shutdown();
    }

}
