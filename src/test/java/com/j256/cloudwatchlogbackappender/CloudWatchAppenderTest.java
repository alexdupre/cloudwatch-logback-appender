package com.j256.cloudwatchlogbackappender;

import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.easymock.EasyMock.getCurrentArguments;
import static org.easymock.EasyMock.isA;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Collections;
import java.util.List;

import org.easymock.IAnswer;
import org.junit.Before;
import org.junit.Test;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.Context;
import ch.qos.logback.core.LogbackException;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.spi.FilterReply;
import ch.qos.logback.core.status.Status;

import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.*;

public class CloudWatchAppenderTest {

	private final LoggerContext LOGGER_CONTEXT = new LoggerContext();

	@Before
	public void before() {
		Ec2InstanceNameConverter.setInstanceName("localhost");
	}

	@Test(timeout = 10000)
	public void testBasic() throws InterruptedException {
		CloudWatchAppender appender = new CloudWatchAppender();
		CloudWatchLogsClient awsLogClient = createMock(CloudWatchLogsClient.class);
		appender.setAwsLogsClient(awsLogClient);

		appender.setMaxBatchSize(1);
		appender.setRegion("region");
		final String logGroup = "pfqoejpfqe";
		appender.setLogGroup(logGroup);
		final String logStream = "pffqjfqjpoqoejpfqe";
		appender.setLogStream(logStream);
		appender.setContext(LOGGER_CONTEXT);
		PatternLayout layout = new PatternLayout();
		layout.setContext(LOGGER_CONTEXT);
		layout.setPattern("[%thread] %level %logger{20} - %msg%n%xThrowable");
		layout.start();
		appender.setLayout(layout);

		String loggerName = "name";
		Level level = Level.DEBUG;
		String message = "fjpewjfpewjfpewjfepowf";
		LoggingEvent event = createEvent(loggerName, level, message, System.currentTimeMillis());

		String threadName = Thread.currentThread().getName();
		final String fullMessage = "[" + threadName + "] " + level + " " + loggerName + " - " + message + "\n";

		final PutLogEventsResponse response = PutLogEventsResponse.builder().build();
		expect(awsLogClient.putLogEvents(isA(PutLogEventsRequest.class))).andAnswer(() -> {
			PutLogEventsRequest request = (PutLogEventsRequest) getCurrentArguments()[0];
			assertEquals(logGroup, request.logGroupName());
			assertEquals(logStream, request.logStreamName());
			List<InputLogEvent> events = request.logEvents();
			assertEquals(1, events.size());
			assertEquals(fullMessage, events.get(0).message());
			return response;
		}).times(2);
		awsLogClient.close();

		// =====================================

		replay(awsLogClient);
		appender.start();
		// for coverage
		appender.start();
		appender.append(event);
		Thread.sleep(100);
		appender.append(event);
		while (appender.getEventsWrittenCount() < 2) {
			Thread.sleep(100);
		}
		appender.stop();
		verify(awsLogClient);
	}

	@Test(timeout = 10000)
	public void testBatchTimeout() throws InterruptedException {
		CloudWatchAppender appender = new CloudWatchAppender();
		CloudWatchLogsClient awsLogClient = createMock(CloudWatchLogsClient.class);
		appender.setAwsLogsClient(awsLogClient);

		appender.setMaxBatchTimeMillis(300);
		appender.setRegion("region");
		final String logGroup = "pfqoejpfqe";
		appender.setLogGroup(logGroup);
		final String logStream = "pffqjfqjpoqoejpfqe";
		appender.setLogStream(logStream);
		appender.setContext(LOGGER_CONTEXT);
		PatternLayout layout = new PatternLayout();
		layout.setContext(LOGGER_CONTEXT);
		layout.setPattern("[%thread] %level %logger{20} - %msg%n%xThrowable");
		layout.start();
		appender.setLayout(layout);

		final PutLogEventsResponse response = PutLogEventsResponse.builder().build();
		expect(awsLogClient.putLogEvents(isA(PutLogEventsRequest.class))).andAnswer(() -> {
			PutLogEventsRequest request = (PutLogEventsRequest) getCurrentArguments()[0];
			assertEquals(logGroup, request.logGroupName());
			assertEquals(logStream, request.logStreamName());
			return response;
		}).anyTimes();
		awsLogClient.close();

		// =====================================

		replay(awsLogClient);
		appender.start();
		// for coverage
		appender.start();

		long now = System.currentTimeMillis();
		appender.append(createEvent("name", Level.DEBUG, "message", null));
		appender.append(createEvent("name", Level.DEBUG, "message", now));
		appender.append(createEvent("name", Level.DEBUG, "message", null));
		appender.append(createEvent("name", Level.DEBUG, "message", now - 1));
		appender.append(createEvent("name", Level.DEBUG, null, null));
		appender.append(createEvent("name", Level.DEBUG, "message", now + 1));
		while (appender.getEventsWrittenCount() < 6) {
			Thread.sleep(100);
		}
		appender.stop();
		verify(awsLogClient);
	}

	@Test(timeout = 10000)
	public void testEmergencyAppender() throws InterruptedException {
		CloudWatchAppender appender = new CloudWatchAppender();
		appender.setInitialWaitTimeMillis(0);
		CloudWatchLogsClient awsLogClient = createMock(CloudWatchLogsClient.class);
		appender.setAwsLogsClient(awsLogClient);

		appender.setMaxBatchSize(1);
		appender.setRegion("region");
		final String logGroup = "pfqoejpfqe";
		appender.setLogGroup(logGroup);
		final String logStream = "pffqjfqjpoqoejpfqe";
		appender.setLogStream(logStream);
		appender.setContext(LOGGER_CONTEXT);
		PatternLayout layout = new PatternLayout();
		layout.setContext(LOGGER_CONTEXT);
		layout.setPattern("[%thread] %level %logger{20} - %msg%n%xThrowable");
		layout.start();
		appender.setLayout(layout);

		final String loggerName = "name";
		final Level level = Level.DEBUG;
		String message = "gerehttrjtrjegr";
		LoggingEvent event = createEvent(loggerName, level, message, System.currentTimeMillis());

		final String threadName = Thread.currentThread().getName();

		expect(awsLogClient.putLogEvents(isA(PutLogEventsRequest.class)))
				.andThrow(new RuntimeException("force emergency log"))
				.anyTimes();
		awsLogClient.close();

		// =====================================

		Appender<ILoggingEvent> emergencyAppender = createMock(Appender.class);
		String emergencyAppenderName = "fjpeowjfwfewf";
		expect(emergencyAppender.getName()).andReturn(emergencyAppenderName);
		expect(emergencyAppender.isStarted()).andReturn(false);
		emergencyAppender.start();
		emergencyAppender.doAppend(isA(ILoggingEvent.class));
		expectLastCall().andAnswer((IAnswer<Void>) () -> {
			ILoggingEvent event1 = (ILoggingEvent) getCurrentArguments()[0];
			if (event1.getLevel() == level) {
				assertEquals(loggerName, event1.getLoggerName());
				assertEquals(threadName, event1.getThreadName());
			} else {
				assertEquals(Level.ERROR, event1.getLevel());
			}
			return null;
		}).times(2);
		emergencyAppender.stop();

		// =====================================

		replay(awsLogClient, emergencyAppender);
		assertNull(appender.getAppender(emergencyAppenderName));
		assertFalse(appender.isAttached(emergencyAppender));
		appender.addAppender(emergencyAppender);
		assertTrue(appender.isAttached(emergencyAppender));
		assertSame(emergencyAppender, appender.getAppender(emergencyAppenderName));
		assertNull(appender.getAppender(null));
		appender.start();
		// for coverage
		appender.start();
		appender.append(event);
		Thread.sleep(100);
		appender.detachAndStopAllAppenders();
		appender.stop();
		verify(awsLogClient, emergencyAppender);
	}

	@Test(timeout = 10000)
	public void testLogClientFailed() throws InterruptedException {
		CloudWatchAppender appender = new CloudWatchAppender();
		appender.setInitialWaitTimeMillis(0);

		appender.setAccessKeyId("not right");
		appender.setSecretKey("not right");
		appender.setInitialWaitTimeMillis(0);

		appender.setMaxBatchSize(1);
		appender.setMaxBatchTimeMillis(100);
		appender.setRegion("us-east-1");
		final String logGroup = "pfqoejpfqe";
		appender.setLogGroup(logGroup);
		final String logStream = "pffqjfqjpoqoejpfqe";
		appender.setLogStream(logStream);
		appender.setContext(LOGGER_CONTEXT);
		PatternLayout layout = new PatternLayout();
		layout.setContext(LOGGER_CONTEXT);
		layout.setPattern("[%thread] %level %logger{20} - %msg%n%xThrowable");
		layout.start();
		appender.setLayout(layout);
		appender.setContext(LOGGER_CONTEXT);
		EmergencyAppender emergencyAppender = new EmergencyAppender();
		appender.addAppender(emergencyAppender);
		appender.start();

		final String loggerName = "name";
		final Level level = Level.DEBUG;
		String message = "hhtthhtrthrhtr";
		LoggingEvent event = createEvent(loggerName, level, message, System.currentTimeMillis());

		appender.append(event);
		while (!emergencyAppender.appended) {
			Thread.sleep(100);
		}
		assertEquals(0, appender.getEventsWrittenCount());
		appender.stop();
	}

	@Test(timeout = 10000)
	public void testBigMessageTruncate() throws InterruptedException {
		CloudWatchAppender appender = new CloudWatchAppender();
		CloudWatchLogsClient awsLogClient = createMock(CloudWatchLogsClient.class);
		appender.setAwsLogsClient(awsLogClient);

		appender.setMaxBatchSize(1);
		appender.setRegion("region");
		final String logGroup = "pfqoejpfqe";
		appender.setLogGroup(logGroup);
		final String logStream = "pffqjfqjpoqoejpfqe";
		appender.setLogStream(logStream);
		appender.setContext(LOGGER_CONTEXT);
		PatternLayout layout = new PatternLayout();
		layout.setContext(LOGGER_CONTEXT);
		layout.setPattern("[%thread] %level %logger{20} - %msg%n%xThrowable");
		layout.start();
		appender.setLayout(layout);
		EmergencyAppender emergency = new EmergencyAppender();
		appender.addAppender(emergency);

		final String loggerName = "name";
		final Level level = Level.DEBUG;
		String message = "hyjjytuytjyjtyhrtfwwef";
		LoggingEvent event = createEvent(loggerName, level, message, System.currentTimeMillis());

		int maxSize = 10;
		appender.setMaxEventMessageSize(maxSize);

		String threadName = Thread.currentThread().getName();
		final String fullMessage =
				"[" + threadName + "] " + level + " " + loggerName + " - " + message.substring(0, maxSize) + "\n";

		final PutLogEventsResponse response = PutLogEventsResponse.builder().build();
		expect(awsLogClient.putLogEvents(isA(PutLogEventsRequest.class))).andAnswer(() -> {
			PutLogEventsRequest request = (PutLogEventsRequest) getCurrentArguments()[0];
			assertEquals(logGroup, request.logGroupName());
			assertEquals(logStream, request.logStreamName());
			List<InputLogEvent> events = request.logEvents();
			assertEquals(1, events.size());
			assertEquals(fullMessage, events.get(0).message());
			return response;
		});
		awsLogClient.close();

		// =====================================

		replay(awsLogClient);
		appender.start();
		// for coverage
		appender.start();
		appender.append(event);
		while (appender.getEventsWrittenCount() < 1) {
			Thread.sleep(100);
		}
		appender.stop();
		verify(awsLogClient);
		assertNull(emergency.event);
	}

	@Test(timeout = 10000)
	public void testBigMessageDrop() throws InterruptedException {
		CloudWatchAppender appender = new CloudWatchAppender();
		CloudWatchLogsClient awsLogClient = createMock(CloudWatchLogsClient.class);
		appender.setAwsLogsClient(awsLogClient);

		appender.setMaxBatchSize(1);
		appender.setRegion("region");
		final String logGroup = "pfqoejpfqe";
		appender.setLogGroup(logGroup);
		final String logStream = "pffqjfqjpoqoejpfqe";
		appender.setLogStream(logStream);
		appender.setContext(LOGGER_CONTEXT);
		PatternLayout layout = new PatternLayout();
		layout.setContext(LOGGER_CONTEXT);
		layout.setPattern("[%thread] %level %logger{20} - %msg%n%xThrowable");
		layout.start();
		appender.setLayout(layout);
		EmergencyAppender emergency = new EmergencyAppender();
		appender.addAppender(emergency);

		final String loggerName = "name";
		final Level level = Level.DEBUG;
		String message = "ytjkuyliuyiuk";
		LoggingEvent event = createEvent(loggerName, level, message, System.currentTimeMillis());

		int maxSize = 10;
		appender.setMaxEventMessageSize(maxSize);
		appender.setTruncateEventMessages(false);

		awsLogClient.close();

		// =====================================

		replay(awsLogClient);
		appender.start();
		// for coverage
		appender.start();
		appender.append(event);
		while (emergency.event == null) {
			Thread.sleep(100);
		}
		appender.stop();
		verify(awsLogClient);

		assertSame(event, emergency.event);
		assertEquals(0, appender.getEventsWrittenCount());
	}

	@Test(timeout = 10000)
	public void testMoreAwsCalls() throws InterruptedException {
		CloudWatchAppender appender = new CloudWatchAppender();
		CloudWatchLogsClient logsClient = createMock(CloudWatchLogsClient.class);
		appender.setTestAwsLogsClient(logsClient);

		appender.setMaxBatchSize(1);
		appender.setRegion("region");
		final String logGroup = "pfqoejpfqe";
		appender.setLogGroup(logGroup);
		final String logStream = "pffqjfqjpoqoejpfqe";
		appender.setLogStream(logStream);
		appender.setContext(LOGGER_CONTEXT);
		PatternLayout layout = new PatternLayout();
		layout.setContext(LOGGER_CONTEXT);
		layout.setPattern("[%thread] %level %logger{20} - %msg%n%xThrowable");
		layout.start();
		appender.setLayout(layout);

		final String loggerName = "name";
		final Level level = Level.DEBUG;
		String message = "kuykregddwqwef4wve";
		LoggingEvent event = createEvent(loggerName, level, message, System.currentTimeMillis());

		String threadName = Thread.currentThread().getName();
		final String fullMessage = "[" + threadName + "] " + level + " " + loggerName + " - " + message + "\n";

		DescribeLogGroupsResponse logGroupsResponse =
				DescribeLogGroupsResponse.builder().logGroups(LogGroup.builder().logGroupName(logGroup).build()).build();
		expect(logsClient.describeLogGroups(isA(DescribeLogGroupsRequest.class))).andReturn(logGroupsResponse);

		DescribeLogStreamsResponse logStreamsResponse = DescribeLogStreamsResponse.builder()
				.logStreams(LogStream.builder().logStreamName(logStream).build()).build();
		expect(logsClient.describeLogStreams(isA(DescribeLogStreamsRequest.class))).andReturn(logStreamsResponse);

		final PutLogEventsResponse putLogEventsResponse = PutLogEventsResponse.builder().build();
		expect(logsClient.putLogEvents(isA(PutLogEventsRequest.class))).andAnswer(() -> {
			PutLogEventsRequest request = (PutLogEventsRequest) getCurrentArguments()[0];
			assertEquals(logGroup, request.logGroupName());
			assertEquals(logStream, request.logStreamName());
			List<InputLogEvent> events = request.logEvents();
			assertEquals(1, events.size());
			assertEquals(fullMessage, events.get(0).message());
			return putLogEventsResponse;
		}).times(2);
		logsClient.close();

		// =====================================

		replay(logsClient);
		appender.start();
		// for coverage
		appender.start();
		appender.append(event);
		Thread.sleep(100);
		appender.append(event);
		while (appender.getEventsWrittenCount() < 2) {
			Thread.sleep(100);
		}
		appender.stop();
		verify(logsClient);
	}

	@Test(timeout = 10000)
	public void testMoreAwsCallsMissingGroupAndStream() throws InterruptedException {
		CloudWatchAppender appender = new CloudWatchAppender();
		CloudWatchLogsClient logsClient = createMock(CloudWatchLogsClient.class);
		appender.setTestAwsLogsClient(logsClient);

		appender.setMaxBatchSize(1);
		appender.setRegion("region");
		final String logGroup = "pfqoejpfqe";
		appender.setLogGroup(logGroup);
		final String logStream = "pffqjfqjpoqoejpfqe";
		appender.setLogStream(logStream);
		appender.setContext(LOGGER_CONTEXT);
		PatternLayout layout = new PatternLayout();
		layout.setContext(LOGGER_CONTEXT);
		layout.setPattern("[%thread] %level %logger{20} - %msg%n%xThrowable");
		layout.start();
		appender.setLayout(layout);

		final String loggerName = "name";
		final Level level = Level.DEBUG;
		String message = "kuuyuyuykkkyjtyh";
		LoggingEvent event = createEvent(loggerName, level, message, System.currentTimeMillis());

		String threadName = Thread.currentThread().getName();
		final String fullMessage = "[" + threadName + "] " + level + " " + loggerName + " - " + message + "\n";

		DescribeLogGroupsResponse logGroupsResponse =
				DescribeLogGroupsResponse.builder().logGroups(Collections.emptyList()).build();
		expect(logsClient.describeLogGroups(isA(DescribeLogGroupsRequest.class))).andReturn(logGroupsResponse);

		CreateLogGroupResponse createLogGroupResponse = CreateLogGroupResponse.builder().build();
		expect(logsClient.createLogGroup(isA(CreateLogGroupRequest.class))).andReturn(createLogGroupResponse);

		DescribeLogStreamsResponse logStreamsResponse =
				DescribeLogStreamsResponse.builder().logStreams(Collections.emptyList()).build();
		expect(logsClient.describeLogStreams(isA(DescribeLogStreamsRequest.class))).andReturn(logStreamsResponse);

		CreateLogStreamResponse createLogStreamResponse = CreateLogStreamResponse.builder().build();
		expect(logsClient.createLogStream(isA(CreateLogStreamRequest.class))).andReturn(createLogStreamResponse);

		final PutLogEventsResponse putLogEventsResponse = PutLogEventsResponse.builder().build();
		expect(logsClient.putLogEvents(isA(PutLogEventsRequest.class))).andAnswer(() -> {
			PutLogEventsRequest request = (PutLogEventsRequest) getCurrentArguments()[0];
			assertEquals(logGroup, request.logGroupName());
			assertEquals(logStream, request.logStreamName());
			List<InputLogEvent> events = request.logEvents();
			assertEquals(1, events.size());
			assertEquals(fullMessage, events.get(0).message());
			return putLogEventsResponse;
		}).times(2);
		logsClient.close();

		// =====================================

		replay(logsClient);
		appender.start();
		// for coverage
		appender.start();
		appender.append(event);
		Thread.sleep(100);
		appender.append(event);
		while (appender.getEventsWrittenCount() < 2) {
			Thread.sleep(100);
		}
		appender.stop();
		verify(logsClient);
	}

	@Test(timeout = 10000)
	public void testCoverage() {
		CloudWatchAppender appender = new CloudWatchAppender();
		appender.setInitialWaitTimeMillis(0);
		appender.detachAndStopAllAppenders();
		// stop before starting
		appender.stop();
		assertFalse(appender.isWarningMessagePrinted());
		System.err.println("Expected warning on next line");
		appender.append(null);
		appender.append(null);
		assertTrue(appender.isWarningMessagePrinted());
		try {
			appender.start();
			fail("Should have thrown");
		} catch (IllegalStateException ise) {
			// expected
		}
		appender.setRegion("region");
		try {
			appender.start();
			fail("Should have thrown");
		} catch (IllegalStateException ise) {
			// expected
		}
		appender.setLogGroup(" wrong ");
		try {
			appender.start();
			fail("Should have thrown");
		} catch (IllegalStateException ise) {
			// expected
		}
		appender.setLogGroup("log-group");
		try {
			appender.start();
			fail("Should have thrown");
		} catch (IllegalStateException ise) {
			// expected
		}
		appender.setLogStream("log-stream");
		try {
			appender.start();
			fail("Should have thrown");
		} catch (IllegalStateException ise) {
			// expected
		}
		PatternLayout layout = new PatternLayout();
		layout.setContext(LOGGER_CONTEXT);
		layout.setPattern("x");
		layout.start();
		appender.setLayout(layout);
		appender.stop();

		appender.setMaxBatchTimeMillis(1000);
		appender.setMaxQueueWaitTimeMillis(1000);
		appender.setInternalQueueSize(1);
		appender.setCreateLogDests(true);
		appender.setEndpointUrl("http://localhost:4566");
		appender.setRetentionDays(7);
		appender.setWaitForAllEvents(false);
		appender.setDisableAwsMetadata(true);

		try {
			appender.iteratorForAppenders();
			fail("should have thrown");
		} catch (UnsupportedOperationException uoe) {
			// expected
		}

		assertNull(appender.getAppender("foo"));
		assertNull(appender.getAppender(null));
		assertNull(appender.getAppender(EmergencyAppender.NAME));

		// yes we are calling ourselves
		EmergencyAppender nullAppender = new EmergencyAppender();
		assertFalse(appender.detachAppender(nullAppender));
		assertFalse(appender.detachAppender(EmergencyAppender.NAME));
		appender.addAppender(nullAppender);

		assertNull(appender.getAppender("foo"));
		assertNull(appender.getAppender(null));
		assertSame(nullAppender, appender.getAppender(EmergencyAppender.NAME));

		appender.addAppender(new EmergencyAppender());
		assertTrue(appender.detachAppender(nullAppender));
		appender.addAppender(new EmergencyAppender());
		assertFalse(appender.detachAppender("something"));
		assertTrue(appender.detachAppender(EmergencyAppender.NAME));
		assertNull(appender.getAppender(EmergencyAppender.NAME));
	}

	@Test(timeout = 10000)
	public void testWaitForAllEvents() {
		CloudWatchAppender appender = new CloudWatchAppender();
		CloudWatchLogsClient awsLogClient = createMock(CloudWatchLogsClient.class);
		appender.setAwsLogsClient(awsLogClient);

		// large enough that the writer thread would still be waiting for its batch when we stop the appender
		appender.setMaxBatchSize(100);
		appender.setMaxBatchTimeMillis(60000);
		appender.setRegion("region");
		final String logGroup = "pfqoejpfqe";
		appender.setLogGroup(logGroup);
		final String logStream = "pffqjfqjpoqoejpfqe";
		appender.setLogStream(logStream);
		appender.setContext(LOGGER_CONTEXT);
		PatternLayout layout = new PatternLayout();
		layout.setContext(LOGGER_CONTEXT);
		layout.setPattern("%msg");
		layout.start();
		appender.setLayout(layout);

		final PutLogEventsResponse response = PutLogEventsResponse.builder().build();
		expect(awsLogClient.putLogEvents(isA(PutLogEventsRequest.class))).andAnswer(() -> {
			PutLogEventsRequest request = (PutLogEventsRequest) getCurrentArguments()[0];
			assertEquals(logGroup, request.logGroupName());
			assertEquals(logStream, request.logStreamName());
			List<InputLogEvent> events = request.logEvents();
			assertEquals(3, events.size());
			assertEquals("one", events.get(0).message());
			assertEquals("two", events.get(1).message());
			assertEquals("three", events.get(2).message());
			return response;
		});
		awsLogClient.close();

		// =====================================

		replay(awsLogClient);
		appender.start();
		long timeStamp = System.currentTimeMillis();
		appender.append(createEvent("name", Level.DEBUG, "one", timeStamp));
		appender.append(createEvent("name", Level.DEBUG, "two", timeStamp + 1));
		appender.append(createEvent("name", Level.DEBUG, "three", timeStamp + 2));
		// the appender should post the queued events instead of dropping them on the floor
		appender.stop();
		assertEquals(3, appender.getEventsWrittenCount());
		verify(awsLogClient);
	}

	@Test(timeout = 10000)
	public void testRetentionDays() throws InterruptedException {
		CloudWatchAppender appender = new CloudWatchAppender();
		CloudWatchLogsClient logsClient = createMock(CloudWatchLogsClient.class);
		appender.setTestAwsLogsClient(logsClient);

		appender.setMaxBatchSize(1);
		appender.setRegion("region");
		final String logGroup = "pfqoejpfqe";
		appender.setLogGroup(logGroup);
		final String logStream = "pffqjfqjpoqoejpfqe";
		appender.setLogStream(logStream);
		appender.setContext(LOGGER_CONTEXT);
		final int retentionDays = 14;
		appender.setRetentionDays(retentionDays);
		PatternLayout layout = new PatternLayout();
		layout.setContext(LOGGER_CONTEXT);
		layout.setPattern("%msg");
		layout.start();
		appender.setLayout(layout);

		DescribeLogGroupsResponse logGroupsResponse =
				DescribeLogGroupsResponse.builder().logGroups(Collections.emptyList()).build();
		expect(logsClient.describeLogGroups(isA(DescribeLogGroupsRequest.class))).andReturn(logGroupsResponse);
		expect(logsClient.createLogGroup(isA(CreateLogGroupRequest.class)))
				.andReturn(CreateLogGroupResponse.builder().build());

		final PutRetentionPolicyResponse retentionResponse = PutRetentionPolicyResponse.builder().build();
		expect(logsClient.putRetentionPolicy(isA(PutRetentionPolicyRequest.class))).andAnswer(() -> {
			PutRetentionPolicyRequest request = (PutRetentionPolicyRequest) getCurrentArguments()[0];
			assertEquals(logGroup, request.logGroupName());
			assertEquals(Integer.valueOf(retentionDays), request.retentionInDays());
			return retentionResponse;
		});

		DescribeLogStreamsResponse logStreamsResponse =
				DescribeLogStreamsResponse.builder().logStreams(Collections.emptyList()).build();
		expect(logsClient.describeLogStreams(isA(DescribeLogStreamsRequest.class))).andReturn(logStreamsResponse);
		expect(logsClient.createLogStream(isA(CreateLogStreamRequest.class)))
				.andReturn(CreateLogStreamResponse.builder().build());

		expect(logsClient.putLogEvents(isA(PutLogEventsRequest.class)))
				.andReturn(PutLogEventsResponse.builder().build())
				.atLeastOnce();
		logsClient.close();

		// =====================================

		replay(logsClient);
		appender.start();
		appender.append(createEvent("name", Level.DEBUG, "message", System.currentTimeMillis()));
		while (appender.getEventsWrittenCount() < 1) {
			Thread.sleep(10);
		}
		appender.stop();
		verify(logsClient);
	}

	private LoggingEvent createEvent(String name, Level level, String message, Long time) {
		LoggingEvent event = new LoggingEvent();
		event.setLoggerName(name);
		event.setLevel(level);
		event.setMessage(message);
		if (time != null) {
			event.setTimeStamp(time);
		}
		event.setMDCPropertyMap(Collections.emptyMap());
		return event;
	}

	private static class EmergencyAppender implements Appender<ILoggingEvent> {

		public static final String NAME = "emergency";

		ILoggingEvent event;
		volatile boolean appended;

		@Override
		public void start() {
		}

		@Override
		public void stop() {
		}

		@Override
		public boolean isStarted() {
			return false;
		}

		@Override
		public void setContext(Context context) {
		}

		@Override
		public Context getContext() {
			return null;
		}

		@Override
		public void addStatus(Status status) {
		}

		@Override
		public void addInfo(String msg) {
		}

		@Override
		public void addInfo(String msg, Throwable ex) {
		}

		@Override
		public void addWarn(String msg) {
		}

		@Override
		public void addWarn(String msg, Throwable ex) {
		}

		@Override
		public void addError(String msg) {
		}

		@Override
		public void addError(String msg, Throwable ex) {
		}

		@Override
		public void addFilter(Filter<ILoggingEvent> newFilter) {
		}

		@Override
		public void clearAllFilters() {
		}

		@Override
		public List<Filter<ILoggingEvent>> getCopyOfAttachedFiltersList() {
			return null;
		}

		@Override
		public FilterReply getFilterChainDecision(ILoggingEvent event) {
			return null;
		}

		@Override
		public String getName() {
			return NAME;
		}

		@Override
		public void doAppend(ILoggingEvent event) throws LogbackException {
			this.event = event;
			appended = true;
		}

		@Override
		public void setName(String name) {
		}
	}
}
