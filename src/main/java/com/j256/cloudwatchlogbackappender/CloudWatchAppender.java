package com.j256.cloudwatchlogbackappender;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.Layout;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import ch.qos.logback.core.spi.AppenderAttachable;
import org.slf4j.Marker;

import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.SdkSystemSetting;
import software.amazon.awssdk.imds.Ec2MetadataClient;
import software.amazon.awssdk.imds.Ec2MetadataResponse;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClientBuilder;
import software.amazon.awssdk.services.cloudwatchlogs.model.*;

/**
 * CloudWatch log appender for logback.
 *
 * @author graywatson
 */
public class CloudWatchAppender extends UnsynchronizedAppenderBase<ILoggingEvent>
		implements AppenderAttachable<ILoggingEvent> {

	/** size of batch to write to cloudwatch api */
	private static final int DEFAULT_MAX_BATCH_SIZE = 128;
	/** time in millis to wait until we have a bunch of events to write */
	private static final long DEFAULT_MAX_BATCH_TIME_MILLIS = 5000;
	/** internal event queue size before we drop log requests on the floor */
	private static final int DEFAULT_INTERNAL_QUEUE_SIZE = 8192;
	/** create log destination group and stream when we startup */
	private static final boolean DEFAULT_CREATE_LOG_DESTS = true;
	/** max time to wait in millis before dropping a log event on the floor */
	private static final long DEFAULT_MAX_QUEUE_WAIT_TIME_MILLIS = 100;
	/** time to wait to initialize which helps when application is starting up */
	private static final long DEFAULT_INITIAL_WAIT_TIME_MILLIS = 0;
	/** time to wait for the writer thread to drain its queue when we are stopping */
	private static final long DRAIN_WAIT_TIME_MILLIS = 5000;
	/** time to wait for the writer thread to quit when we are not draining its queue */
	private static final long INTERRUPT_WAIT_TIME_MILLIS = 1000;
	/** property looked for to find the aws access-key-id */
	public static final String AWS_ACCESS_KEY_ID_PROPERTY = "cloudwatchappender.aws.accessKeyId";
	/** property looked for to find the aws secret-key */
	public static final String AWS_SECRET_KEY_PROPERTY = "cloudwatchappender.aws.secretKey";
	public static final int DEFAULT_MAX_EVENT_MESSAGE_SIZE = 256 * 1024;
	public static final boolean DEFAULT_TRUNCATE_EVENT_MESSAGES = true;
	public static final boolean DEFAULT_COPY_EVENTS = true;
	public static final boolean DEFAULT_PRINT_REJECTED_EVENTS = false;
	public static final Pattern LOG_GROUP_PATTERN = Pattern.compile("[.\\-_/#A-Za-z0-9]+");
	public static final boolean DEFAULT_WAIT_FOR_ALL_EVENTS = true;
	public static final boolean DEFAULT_DISABLE_AWS_METADATA = false;

	/**
	 * Marker pushed onto the queue to wake up the writer thread when we are stopping. Compared by identity.
	 */
	private static final ILoggingEvent SHUTDOWN_EVENT = new LoggingEvent();

	private String accessKeyId;
	private String secretKey;
	private String region;
	private URI endpointUrl;
	private String logGroupName;
	private String logStreamName;
	private Layout<ILoggingEvent> layout;
	private Appender<ILoggingEvent> emergencyAppender;
	private int maxBatchSize = DEFAULT_MAX_BATCH_SIZE;
	private long maxBatchTimeMillis = DEFAULT_MAX_BATCH_TIME_MILLIS;
	private long maxQueueWaitTimeMillis = DEFAULT_MAX_QUEUE_WAIT_TIME_MILLIS;
	private int internalQueueSize = DEFAULT_INTERNAL_QUEUE_SIZE;
	private boolean createLogDests = DEFAULT_CREATE_LOG_DESTS;
	private long initialWaitTimeMillis = DEFAULT_INITIAL_WAIT_TIME_MILLIS;
	private int maxEventMessageSize = DEFAULT_MAX_EVENT_MESSAGE_SIZE;
	private boolean truncateEventMessages = DEFAULT_TRUNCATE_EVENT_MESSAGES;
	private boolean copyEvents = DEFAULT_COPY_EVENTS;
	private boolean printRejectedEvents = DEFAULT_PRINT_REJECTED_EVENTS;
	private int retentionDays;
	private boolean waitForAllEvents = DEFAULT_WAIT_FOR_ALL_EVENTS;
	private boolean disableAwsMetadata = DEFAULT_DISABLE_AWS_METADATA;

	private CloudWatchLogsClient awsLogsClient;
	private CloudWatchLogsClient testAwsLogsClient;
	private volatile long eventsWrittenCount;

	private BlockingQueue<ILoggingEvent> loggingEventQueue;
	private Thread cloudWatchWriterThread;
	private Thread shutdownHook;
	private volatile boolean shutdown;
	private final ThreadLocal<Boolean> stopMessagesThreadLocal = new ThreadLocal<>();
	private volatile boolean warningMessagePrinted;
	private final InputLogEventComparator inputLogEventComparator = new InputLogEventComparator();

	public CloudWatchAppender() {
		// for spring
	}

	/**
	 * After all of the setters, call initial to setup the appender.
	 */
	@Override
	public void start() {
		if (started) {
			return;
		}
		/*
		 * NOTE: as we startup here, we can't make any log calls so we can't make any RPC calls or anything without
		 * going recursive.
		 */
		if (MiscUtils.isBlank(region)) {
			throw new IllegalStateException("Region not set or invalid for appender: " + region);
		}
		if (MiscUtils.isBlank(logGroupName)) {
			throw new IllegalStateException("Log group name not set or invalid for appender: " + logGroupName);
		}
		if (!LOG_GROUP_PATTERN.matcher(logGroupName).matches()) {
			throw new IllegalStateException("Log group name does not match AWS acceptance pattern '" + LOG_GROUP_PATTERN
					+ "': " + logGroupName);
		}
		if (MiscUtils.isBlank(logStreamName)) {
			throw new IllegalStateException("Log stream name not set or invalid for appender: " + logStreamName);
		}
		if (layout == null) {
			throw new IllegalStateException("Layout was not set for appender");
		}

		if (disableAwsMetadata) {
			// stop the converter from reaching out to the ECS metadata service
			TaskIdConverter.disableLookup();
		}

		loggingEventQueue = new ArrayBlockingQueue<>(internalQueueSize);
		shutdown = false;

		// create our writer thread in the background
		cloudWatchWriterThread = new Thread(new CloudWatchWriter(), getClass().getSimpleName());
		cloudWatchWriterThread.setDaemon(true);
		cloudWatchWriterThread.start();

		/*
		 * The writer thread is a daemon so it would otherwise be killed before it had a chance to post the events that
		 * are still in the queue.
		 */
		shutdownHook = new Thread(this::stop, getClass().getSimpleName() + "Shutdown");
		Runtime.getRuntime().addShutdownHook(shutdownHook);

		if (emergencyAppender != null && !emergencyAppender.isStarted()) {
			emergencyAppender.start();
		}
		super.start();
	}

	@Override
	public void stop() {
		if (!started) {
			return;
		}

		removeShutdownHook();

		shutdown = true;
		long joinMillis;
		if (waitForAllEvents) {
			/*
			 * Wake up the writer thread without interrupting it so that it can post the events that are still in the
			 * queue instead of aborting whatever request is in flight.
			 */
			loggingEventQueue.offer(SHUTDOWN_EVENT);
			joinMillis = DRAIN_WAIT_TIME_MILLIS;
		} else {
			cloudWatchWriterThread.interrupt();
			joinMillis = INTERRUPT_WAIT_TIME_MILLIS;
		}
		try {
			cloudWatchWriterThread.join(joinMillis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		if (awsLogsClient != null) {
			awsLogsClient.close();
			awsLogsClient = null;
		}

		super.stop();
	}

	@Override
	protected void append(ILoggingEvent loggingEvent) {

		// check wiring
		if (loggingEventQueue == null) {
			if (!warningMessagePrinted) {
				System.err.println(getClass().getSimpleName() + " not started correctly, ignoring all log messages");
				warningMessagePrinted = true;
			}
			return;
		}

		// skip it if we just went recursive
		Boolean stopped = stopMessagesThreadLocal.get();
		if (stopped != null && stopped) {
			return;
		}

		String message = loggingEvent.getMessage();
		boolean copied = false;
		if (message != null && message.length() > maxEventMessageSize) {
			if (!truncateEventMessages) {
				// if the message is too big and we can't truncate it then just write it to the emergency appender
				appendToEmergencyAppender(loggingEvent);
				return;
			}
			// we copy all of the fields over but with the truncated message
			loggingEvent = copyEvent(loggingEvent, message.substring(0, maxEventMessageSize));
			copied = true;
		}
		/*
		 * Since we are writing the event out in another thread, the default is to copy the event into our internal
		 * queue for transmission.
		 */
		if (!copied) {
			if (copyEvents) {
				loggingEvent = copyEvent(loggingEvent, null);
			} else {
				loggingEvent.prepareForDeferredProcessing();
			}
		}

		try {
			if (!loggingEventQueue.offer(loggingEvent, maxQueueWaitTimeMillis, TimeUnit.MILLISECONDS)) {
				appendToEmergencyAppender(loggingEvent);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			appendToEmergencyAppender(loggingEvent);
		}
	}

	// not-required, default is to use the DefaultAWSCredentialsProviderChain
	public void setAccessKeyId(String accessKeyId) {
		this.accessKeyId = accessKeyId;
	}

	// not-required, default is to use the DefaultAWSCredentialsProviderChain
	public void setSecretKey(String secretKey) {
		this.secretKey = secretKey;
	}

	// not-required, default is the standard endpoint for the region
	public void setEndpointUrl(String endpointUrl) {
		this.endpointUrl = URI.create(endpointUrl);
	}

	// required
	public void setRegion(String region) {
		this.region = region;
	}

	// required
	public void setLogGroup(String logGroupName) {
		this.logGroupName = logGroupName;
	}

	// required
	public void setLogStream(String logStreamName) {
		this.logStreamName = logStreamName;
	}

	// required
	public void setLayout(Layout<ILoggingEvent> layout) {
		this.layout = layout;
	}

	// not-required, default is DEFAULT_MAX_BATCH_SIZE
	public void setMaxBatchSize(int maxBatchSize) {
		this.maxBatchSize = maxBatchSize;
	}

	// not-required, default is DEFAULT_MAX_BATCH_TIME_MILLIS
	public void setMaxBatchTimeMillis(long maxBatchTimeMillis) {
		this.maxBatchTimeMillis = maxBatchTimeMillis;
	}

	// not-required, default is DEFAULT_MAX_QUEUE_WAIT_TIME_MILLIS
	public void setMaxQueueWaitTimeMillis(long maxQueueWaitTimeMillis) {
		this.maxQueueWaitTimeMillis = maxQueueWaitTimeMillis;
	}

	// not-required, default is DEFAULT_INTERNAL_QUEUE_SIZE
	public void setInternalQueueSize(int internalQueueSize) {
		this.internalQueueSize = internalQueueSize;
	}

	// not-required, default is DEFAULT_CREATE_LOG_DESTS
	public void setCreateLogDests(boolean createLogDests) {
		this.createLogDests = createLogDests;
	}

	// not-required, default is 0
	public void setInitialWaitTimeMillis(long initialWaitTimeMillis) {
		this.initialWaitTimeMillis = initialWaitTimeMillis;
	}

	// not required, for testing purposes
	void setAwsLogsClient(CloudWatchLogsClient awsLogsClient) {
		this.awsLogsClient = awsLogsClient;
	}

	// not required, for testing purposes
	void setTestAwsLogsClient(CloudWatchLogsClient testAwsLogsClient) {
		this.testAwsLogsClient = testAwsLogsClient;
	}

	public void setMaxEventMessageSize(int maxEventMessageSize) {
		this.maxEventMessageSize = maxEventMessageSize;
	}

	// not required, default is true
	public void setTruncateEventMessages(boolean truncateEventMessages) {
		this.truncateEventMessages = truncateEventMessages;
	}

	// not required, default is true
	public void setCopyEvents(boolean copyEvents) {
		this.copyEvents = copyEvents;
	}

	// not required, default is false
	public void setPrintRejectedEvents(boolean printRejectedEvents) {
		this.printRejectedEvents = printRejectedEvents;
	}

	/**
	 * Number of days that the log events are retained for. Only applied to a log-group that this appender creates, so
	 * that we don't overwrite a retention policy that was configured elsewhere. 0 (the default) means that the
	 * CloudWatch default of never expiring is used.
	 */
	public void setRetentionDays(int retentionDays) {
		this.retentionDays = retentionDays;
	}

	/**
	 * Set to false (default true) to stop the appender immediately instead of waiting for the events that are still
	 * queued to be written to CloudWatch.
	 */
	public void setWaitForAllEvents(boolean waitForAllEvents) {
		this.waitForAllEvents = waitForAllEvents;
	}

	/**
	 * Set to true (default false) if you are not running under EC2 or ECS so that the appender won't try to download
	 * the instance and task metadata.
	 */
	public void setDisableAwsMetadata(boolean disableAwsMetadata) {
		this.disableAwsMetadata = disableAwsMetadata;
	}

	// not required, for testing purposes
	public static void setEc2MetadataServiceEndpoint(String ec2MetadataServiceEndpoint) {
		System.setProperty(SdkSystemSetting.AWS_EC2_METADATA_SERVICE_ENDPOINT.property(),
				ec2MetadataServiceEndpoint);
	}

	/**
	 * For testing purposes, set the EC2 service override property to the following hostname. Can be "localhost".
	 */
	public static void setEc2InstanceName(String testInstanceName) {
		Ec2InstanceNameConverter.setInstanceName(testInstanceName);
	}

	// for testing purposes
	long getEventsWrittenCount() {
		return eventsWrittenCount;
	}

	// for testing purposes
	boolean isWarningMessagePrinted() {
		return warningMessagePrinted;
	}

	@Override
	public void addAppender(Appender<ILoggingEvent> appender) {
		if (emergencyAppender == null) {
			emergencyAppender = appender;
		} else {
			addWarn("One and only one appender may be attached to " + getClass().getSimpleName());
			addWarn("Ignoring additional appender named [" + appender.getName() + "]");
		}
	}

	@Override
	public Iterator<Appender<ILoggingEvent>> iteratorForAppenders() {
		throw new UnsupportedOperationException("Don't know how to create iterator");
	}

	@Override
	public Appender<ILoggingEvent> getAppender(String name) {
		if (emergencyAppender != null && name != null && name.equals(emergencyAppender.getName())) {
			return emergencyAppender;
		} else {
			return null;
		}
	}

	@Override
	public boolean isAttached(Appender<ILoggingEvent> appender) {
		return (emergencyAppender == appender);
	}

	@Override
	public void detachAndStopAllAppenders() {
		if (emergencyAppender != null) {
			emergencyAppender.stop();
			emergencyAppender = null;
		}
	}

	@Override
	public boolean detachAppender(Appender<ILoggingEvent> appender) {
		if (emergencyAppender == appender) {
			emergencyAppender = null;
			return true;
		} else {
			return false;
		}
	}

	@Override
	public boolean detachAppender(String name) {
		if (emergencyAppender != null && emergencyAppender.getName().equals(name)) {
			emergencyAppender = null;
			return true;
		} else {
			return false;
		}
	}

	/**
	 * Copy the event fields possible replacing the message if not null.
	 */
	private LoggingEvent copyEvent(ILoggingEvent loggingEvent, String message) {
		LoggingEvent newEvent = new LoggingEvent();
		newEvent.setArgumentArray(loggingEvent.getArgumentArray());
		newEvent.setLevel(loggingEvent.getLevel());
		newEvent.setLoggerContextRemoteView(loggingEvent.getLoggerContextVO());
		newEvent.setLoggerName(loggingEvent.getLoggerName());
		if (loggingEvent.getMarkerList() != null) {
			for (Marker marker : loggingEvent.getMarkerList()) {
				newEvent.addMarker(marker);
			}
		}
		newEvent.setMDCPropertyMap(loggingEvent.getMDCPropertyMap());
		if (message == null) {
			newEvent.setMessage(loggingEvent.getMessage());
		} else {
			newEvent.setMessage(message);
		}
		newEvent.setThreadName(loggingEvent.getThreadName());
		IThrowableProxy ithrowableProxy = loggingEvent.getThrowableProxy();
		if (ithrowableProxy instanceof ThrowableProxy) {
			newEvent.setThrowableProxy((ThrowableProxy) ithrowableProxy);
		}
		newEvent.setTimeStamp(loggingEvent.getTimeStamp());
		return newEvent;
	}

	/**
	 * Remove our shutdown hook unless we are being called from inside of it.
	 */
	private void removeShutdownHook() {
		Thread hook = shutdownHook;
		if (hook == null) {
			return;
		}
		shutdownHook = null;
		try {
			Runtime.getRuntime().removeShutdownHook(hook);
		} catch (IllegalStateException ise) {
			// the JVM is already shutting down which means that the hook is the one calling us
		}
	}

	private void appendToEmergencyAppender(ILoggingEvent event) {
		if (emergencyAppender != null) {
			try {
				emergencyAppender.doAppend(event);
				return;
			} catch (Exception outer) {
				// fall through and maybe print below
			}
		}
		if (printRejectedEvents) {
			try {
				System.err.println(getClass().getSimpleName() + " emergency appender didn't handle event: "
						+ layout.doLayout(event));
			} catch (Exception inner) {
				// oh well, we tried
			}
		}
	}

	private void appendToEmergencyAppender(List<ILoggingEvent> events) {
		for (ILoggingEvent event : events) {
			appendToEmergencyAppender(event);
		}
	}

	/**
	 * Background thread that writes the log events to cloudwatch.
	 */
	private class CloudWatchWriter implements Runnable {

		private String logStreamName;
		private boolean initialized;

		@Override
		public void run() {
			Thread thread = Thread.currentThread();
			try {
				Thread.sleep(initialWaitTimeMillis);
			} catch (InterruptedException e) {
				thread.interrupt();
				return;
			}

			List<ILoggingEvent> events = new ArrayList<>(maxBatchSize);
			while (!shutdown && !thread.isInterrupted()) {
				long batchTimeout = System.currentTimeMillis() + maxBatchTimeMillis;
				/*
				 * No shutdown check here on purpose: SHUTDOWN_EVENT is queued behind the events that were appended
				 * before stop() was called, so waiting for it keeps that last batch together.
				 */
				while (!thread.isInterrupted()) {
					long timeoutMillis = batchTimeout - System.currentTimeMillis();
					if (timeoutMillis < 0) {
						break;
					}
					ILoggingEvent loggingEvent;
					try {
						loggingEvent = loggingEventQueue.poll(timeoutMillis, TimeUnit.MILLISECONDS);
					} catch (InterruptedException ex) {
						thread.interrupt();
						break;
					}
					if (loggingEvent == null) {
						// wait timed out
						break;
					}
					if (loggingEvent == SHUTDOWN_EVENT) {
						break;
					}
					events.add(loggingEvent);
					if (events.size() >= maxBatchSize) {
						// batch size exceeded
						break;
					}
				}
				if (!events.isEmpty()) {
					writeEvents(events);
					events.clear();
				}
			}

			/*
			 * We have been interrupted or stopped so write all of the rest of the events and then quit
			 */

			while (true) {
				ILoggingEvent event = loggingEventQueue.poll();
				if (event == null) {
					// nothing else waiting
					break;
				}
				if (event == SHUTDOWN_EVENT) {
					continue;
				}
				events.add(event);
				if (events.size() >= maxBatchSize) {
					writeEvents(events);
					events.clear();
				}
			}
			if (!events.isEmpty()) {
				writeEvents(events);
				events.clear();
			}
			// thread quits here
		}

		private void writeEvents(List<ILoggingEvent> events) {

			if (!initialized) {
				initialized = true;
				Exception exception = null;
				try {
					stopMessagesThreadLocal.set(true);
					if (awsLogsClient == null) {
						createLogsClient();
					} else {
						// mostly here for testing
						logStreamName = buildLogStreamName();
					}
				} catch (Exception e) {
					exception = e;
				} finally {
					stopMessagesThreadLocal.set(false);
				}
				if (exception != null) {
					appendEvent(Level.ERROR, "Problems initializing cloudwatch writer", exception);
				}
			}

			// if we didn't get an aws logs-client then just write to the emergency appender (if any)
			if (awsLogsClient == null) {
				appendToEmergencyAppender(events);
				return;
			}

			// we need this in case our RPC calls create log output which we don't want to then log again
			stopMessagesThreadLocal.set(true);
			Exception exception = null;
			try {
				List<InputLogEvent> logEvents = new ArrayList<InputLogEvent>(events.size());
				for (ILoggingEvent event : events) {
					String message = layout.doLayout(event);
					InputLogEvent logEvent =
							InputLogEvent.builder().timestamp(event.getTimeStamp()).message(message).build();
					logEvents.add(logEvent);
				}
				// events must be in sorted order according to AWS otherwise an exception is thrown
				logEvents.sort(inputLogEventComparator);

				PutLogEventsRequest request = PutLogEventsRequest.builder().logGroupName(logGroupName).logStreamName(logStreamName).logEvents(logEvents).build();
				awsLogsClient.putLogEvents(request);
				eventsWrittenCount += logEvents.size();
			} catch (Exception e) {
				// catch everything else to make sure we don't quit the thread
				exception = e;
			} finally {
				if (exception != null) {
					// we do this because we don't want to go recursive
					events.add(makeEvent(Level.ERROR,
							"Exception thrown when creating logging " + events.size() + " events", exception));
					appendToEmergencyAppender(events);
				}
				stopMessagesThreadLocal.set(false);
			}
		}

		private void createLogsClient() {
			AwsCredentialsProvider credentialProvider;
			if (MiscUtils.isBlank(accessKeyId)) {
				// try to use our class properties
				accessKeyId = System.getProperty(AWS_ACCESS_KEY_ID_PROPERTY);
				secretKey = System.getProperty(AWS_SECRET_KEY_PROPERTY);
			}
			if (MiscUtils.isBlank(accessKeyId)) {
				// if we are still blank then use the default credentials provider
				// note: build() and not create(), which returns a shared singleton that we would close in stop()
				credentialProvider = DefaultCredentialsProvider.builder().build();
			} else {
				credentialProvider = StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKeyId, secretKey));
			}
			CloudWatchLogsClient client;
			if (testAwsLogsClient == null) {
				CloudWatchLogsClientBuilder builder =
						CloudWatchLogsClient.builder().credentialsProvider(credentialProvider).region(Region.of(region));
				if (endpointUrl != null) {
					builder.endpointOverride(endpointUrl);
				}
				client = builder.build();
			} else {
				client = testAwsLogsClient;
			}
			if (!disableAwsMetadata) {
				try {
					lookupInstanceName();
				} catch (Exception e) {
					appendEvent(Level.ERROR, "Problems looking up instance-name", e);
				}
			}
			logStreamName = buildLogStreamName();
			verifyLogGroupExists(client);
			verifyLogStreamExists(client);
			awsLogsClient = client;
		}

		private void verifyLogGroupExists(CloudWatchLogsClient client) {
			DescribeLogGroupsRequest request = DescribeLogGroupsRequest.builder().logGroupNamePrefix(logGroupName).build();
			DescribeLogGroupsResponse result = client.describeLogGroups(request);
			for (LogGroup group : result.logGroups()) {
				if (logGroupName.equals(group.logGroupName())) {
					return;
				}
			}
			if (createLogDests) {
				CreateLogGroupRequest createRequest = CreateLogGroupRequest.builder().logGroupName(logGroupName).build();
				client.createLogGroup(createRequest);
				appendEvent(Level.INFO, "Created log-group '" + logGroupName + "'", null);
				if (retentionDays > 0) {
					PutRetentionPolicyRequest retentionRequest = PutRetentionPolicyRequest.builder()
							.logGroupName(logGroupName).retentionInDays(retentionDays).build();
					client.putRetentionPolicy(retentionRequest);
					appendEvent(Level.INFO,
							"Set retention of log-group '" + logGroupName + "' to " + retentionDays + " days", null);
				}
			} else {
				appendEvent(Level.WARN, "Log-group '" + logGroupName + "' doesn't exist and not created", null);
			}
		}

		private void verifyLogStreamExists(CloudWatchLogsClient client) {
			DescribeLogStreamsRequest request = DescribeLogStreamsRequest.builder().logGroupName(logGroupName)
					.logStreamNamePrefix(logStreamName).build();
			DescribeLogStreamsResponse result = client.describeLogStreams(request);
			for (LogStream stream : result.logStreams()) {
				if (logStreamName.equals(stream.logStreamName())) {
					return;
				}
			}
			if (createLogDests) {
				CreateLogStreamRequest createRequest = CreateLogStreamRequest.builder().logGroupName(logGroupName).logStreamName(logStreamName).build();
				client.createLogStream(createRequest);
				appendEvent(Level.INFO, "Created log-stream '" + logStreamName + "' for group '" + logGroupName + "'", null);
			} else {
				appendEvent(Level.WARN, "Log-stream '" + logStreamName + "' doesn't exist and not created", null);
			}
		}

		private String buildLogStreamName() {
			String name = CloudWatchAppender.this.logStreamName;
			if (name.indexOf('%') < 0) {
				return name;
			}
			/*
			 * Little bit of a hack here. We use one of our layout instances to format the _name_ of the log-stream.
			 * This allows us to support the same %token that are supported by the messages.
			 */
			Ec2PatternLayout nameLayout = new Ec2PatternLayout();
			nameLayout.setPattern(name);
			nameLayout.setContext(context);
			nameLayout.start();
			// somewhat random logging event although the time-stamp is important
			LoggingEvent event = new LoggingEvent();
			event.setLevel(Level.INFO);
			event.setLoggerName("logStreamName");
			event.setMessage("log stream name");
			event.setTimeStamp(System.currentTimeMillis());
			event.setMDCPropertyMap(Collections.emptyMap());
			name = nameLayout.doLayout(event);
			// replace the only character that cloudwatch barfs on: Member must satisfy regex pattern: [^:*]*
			name = name.replace(':', '_');
			return name;
		}

		private void lookupInstanceName() {
			try (Ec2MetadataClient client = Ec2MetadataClient.create()) {
				Ec2MetadataResponse response = client.get("/latest/meta-data/instance-id");
				String instanceId = response.asString();
				if (instanceId == null) {
					return;
				}
				Ec2InstanceIdConverter.setInstanceId(instanceId);
				try {
					response = client.get("/latest/meta-data/tags/instance/Name");
					String instanceName = response.asString().trim();
					if (!instanceName.isEmpty()) {
						Ec2InstanceNameConverter.setInstanceName(instanceName);
						return;
					}
					appendEvent(Level.INFO, "Could not find EC2 instance name", null);
				} catch (AwsServiceException ase) {
					appendEvent(Level.WARN, "Looking up EC2 instance-name threw", ase);
				}
				// if we can't lookup the instance name then set it as the instance-id
				Ec2InstanceNameConverter.setInstanceName(instanceId);
			}
		}

		private void appendEvent(Level level, String message, Throwable th) {
			append(makeEvent(level, message, th));
		}

		private LoggingEvent makeEvent(Level level, String message, Throwable th) {
			LoggingEvent event = new LoggingEvent();
			event.setLoggerName(CloudWatchAppender.class.getName());
			event.setLevel(level);
			event.setMessage(message);
			event.setTimeStamp(System.currentTimeMillis());
			if (th != null) {
				event.setThrowableProxy(new ThrowableProxy(th));
			}
			event.setMDCPropertyMap(Collections.emptyMap());
			return event;
		}
	}

	/**
	 * Compares a log event by it's timestamp value.
	 */
	private static class InputLogEventComparator implements Comparator<InputLogEvent> {
		@Override
		public int compare(InputLogEvent o1, InputLogEvent o2) {
			if (o1.timestamp() == null) {
				if (o2.timestamp() == null) {
					return 0;
				} else {
					// null - long
					return -1;
				}
			} else if (o2.timestamp() == null) {
				// long - null
				return 1;
			} else {
				return o1.timestamp().compareTo(o2.timestamp());
			}
		}
	}
}
