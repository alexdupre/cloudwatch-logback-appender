package com.j256.cloudwatchlogbackappender;

import static org.junit.Assert.assertEquals;

import org.junit.After;
import org.junit.Test;

public class TaskIdConverterTest extends BaseConverterTest {

	private static final String TASK_ID = "8bbe67c9363a457894068e2259f5690c";
	private static final String TASK_METADATA = "{\"Cluster\":\"default\","
			+ "\"TaskARN\":\"arn:aws:ecs:us-east-1:012345678901:task/appname/" + TASK_ID + "\","
			+ "\"Family\":\"appname\",\"Revision\":\"5\"}";

	@After
	public void after() {
		// restore the lookup for the other tests
		TaskIdConverter.setTaskId(null);
	}

	@Test(timeout = 10000)
	public void testTaskId() throws InterruptedException {
		TaskIdConverter.setTaskId(TASK_ID);
		expectLogStream("logstream-%taskId", "logstream-" + TASK_ID);
	}

	@Test(timeout = 10000)
	public void testNotInEcs() throws InterruptedException {
		/*
		 * The ECS metadata environment variable isn't set when we are not running under ECS so the converter should
		 * tell us that instead of trying to reach out to the metadata service.
		 */
		expectLogStream("logstream-%taskId", "logstream-not-in-ecs");
	}

	@Test(timeout = 10000)
	public void testDisableAwsMetadata() throws InterruptedException {
		appender.setDisableAwsMetadata(true);
		expectLogStream("logstream-%taskId", "logstream-unknown");
	}

	@Test(timeout = 10000)
	public void testDisableAwsMetadataKeepsExplicitTaskId() throws InterruptedException {
		TaskIdConverter.setTaskId(TASK_ID);
		appender.setDisableAwsMetadata(true);
		expectLogStream("logstream-%taskId", "logstream-" + TASK_ID);
	}

	@Test
	public void testParseTaskId() {
		assertEquals(TASK_ID, TaskIdConverter.parseTaskId(TASK_METADATA));
	}

	@Test
	public void testParseTaskIdBadMetadata() {
		assertEquals("unknown", TaskIdConverter.parseTaskId("this is not json"));
		assertEquals("unknown", TaskIdConverter.parseTaskId("[\"a list, not a map\"]"));
		assertEquals("unknown", TaskIdConverter.parseTaskId("{\"Cluster\":\"default\"}"));
		assertEquals("unknown", TaskIdConverter.parseTaskId("{\"TaskARN\":12345}"));
		assertEquals("unknown", TaskIdConverter.parseTaskId("{\"TaskARN\":\"no-slash-in-here\"}"));
		assertEquals("unknown", TaskIdConverter.parseTaskId("{\"TaskARN\":\"trailing/\"}"));
	}
}
