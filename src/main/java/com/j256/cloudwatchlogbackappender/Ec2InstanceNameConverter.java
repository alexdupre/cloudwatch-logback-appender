package com.j256.cloudwatchlogbackappender;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * Converter which knows about the instance-name
 * 
 * @author graywatson
 */
public class Ec2InstanceNameConverter extends ClassicConverter {

	private static final String DEFAULT_INSTANCE_NAME = "unknown";

	private static String instanceName = DEFAULT_INSTANCE_NAME;

	/**
	 * Default constructor, used by logback when it builds the conversion word.
	 */
	public Ec2InstanceNameConverter() {
		// for logback
	}

	@Override
	public String convert(ILoggingEvent event) {
		return instanceName;
	}

	/**
	 * Set the instance-name explicitly instead of having it looked up from the EC2 metadata.
	 * 
	 * @param instanceName Instance-name to report, or null to restore the "unknown" default.
	 */
	public static void setInstanceName(String instanceName) {
		if (instanceName == null) {
			Ec2InstanceNameConverter.instanceName = DEFAULT_INSTANCE_NAME;
		} else {
			Ec2InstanceNameConverter.instanceName = instanceName;
		}
	}
}
