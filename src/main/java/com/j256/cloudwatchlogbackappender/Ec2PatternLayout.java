package com.j256.cloudwatchlogbackappender;

import java.util.Map;
import java.util.function.Supplier;

import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.core.pattern.DynamicConverter;

/**
 * Extension of the pattern layout which handles some replacements specific to EC2 and ECS. It replaces "%instance",
 * "%instanceName", and "%in" with the instance name. It also replaces "%instanceId" and "%iid" with the instance-id and
 * "%taskId" with the ECS task-id.
 * 
 * @author graywatson
 */
public class Ec2PatternLayout extends PatternLayout {

	public Ec2PatternLayout() {
		/*
		 * These are registered in the per-instance map and not in the static PatternLayout.DEFAULT_CONVERTER_SUPPLIER_MAP
		 * so that we don't inject our conversion words into every other layout in the application.
		 */
		Map<String, Supplier<DynamicConverter>> converterMap = getInstanceConverterMap();
		converterMap.put("instance", Ec2InstanceNameConverter::new);
		converterMap.put("instanceName", Ec2InstanceNameConverter::new);
		converterMap.put("in", Ec2InstanceNameConverter::new);
		converterMap.put("instanceId", Ec2InstanceIdConverter::new);
		converterMap.put("iid", Ec2InstanceIdConverter::new);
		converterMap.put("taskId", TaskIdConverter::new);
		converterMap.put("uuid", UuidConverter::new);
		converterMap.put("hostName", HostNameConverter::new);
		converterMap.put("host", HostNameConverter::new);
		converterMap.put("hostAddress", HostAddressConverter::new);
		converterMap.put("address", HostAddressConverter::new);
		converterMap.put("addr", HostAddressConverter::new);
		converterMap.put("systemProperty", SystemPropertyConverter::new);
		converterMap.put("property", SystemPropertyConverter::new);
		converterMap.put("prop", SystemPropertyConverter::new);
		converterMap.put("systemEnviron", SystemEnvironConverter::new);
		converterMap.put("environ", SystemEnvironConverter::new);
		converterMap.put("env", SystemEnvironConverter::new);
	}
}
