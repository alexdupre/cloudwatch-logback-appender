package com.j256.cloudwatchlogbackappender;

import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.getCurrentArguments;
import static org.easymock.EasyMock.isA;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.Assert.assertEquals;

import java.util.Collections;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.LoggingEvent;

import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.PutLogEventsRequest;
import software.amazon.awssdk.services.cloudwatchlogs.model.PutLogEventsResponse;

public abstract class BaseConverterTest {

	protected final String LOG_GROUP = "pfqoejpfqe";
	protected final LoggerContext LOGGER_CONTEXT = new LoggerContext();
	protected final CloudWatchAppender appender = new CloudWatchAppender();

	{
		appender.setMaxBatchSize(1);
		appender.setMaxBatchTimeMillis(0);
		appender.setRegion("region");
		appender.setContext(LOGGER_CONTEXT);
		appender.setLogGroup(LOG_GROUP);
		appender.setInitialWaitTimeMillis(0);
	}

	/**
	 * Log a single event through the appender with the log-stream name set to the pattern, and verify that the pattern
	 * expanded into the expected log-stream name.
	 */
	protected void expectLogStream(String logStreamPattern, final String expectedLogStream)
			throws InterruptedException {

		CloudWatchLogsClient awsLogClient = createMock(CloudWatchLogsClient.class);
		appender.setAwsLogsClient(awsLogClient);
		appender.setLogStream(logStreamPattern);

		PatternLayout layout = new PatternLayout();
		layout.setPattern("%msg");
		layout.setContext(LOGGER_CONTEXT);
		layout.start();
		appender.setLayout(layout);

		LoggingEvent event = new LoggingEvent();
		event.setTimeStamp(System.currentTimeMillis());
		event.setLoggerName("name");
		event.setLevel(Level.DEBUG);
		event.setMessage("message");
		event.setMDCPropertyMap(Collections.emptyMap());

		final PutLogEventsResponse response = PutLogEventsResponse.builder().build();
		expect(awsLogClient.putLogEvents(isA(PutLogEventsRequest.class))).andAnswer(() -> {
			PutLogEventsRequest request = (PutLogEventsRequest) getCurrentArguments()[0];
			assertEquals(LOG_GROUP, request.logGroupName());
			assertEquals(expectedLogStream, request.logStreamName());
			return response;
		});
		awsLogClient.close();

		// =====================================

		replay(awsLogClient);
		appender.start();
		appender.append(event);
		while (appender.getEventsWrittenCount() < 1) {
			Thread.sleep(10);
		}
		appender.stop();
		verify(awsLogClient);
	}
}
