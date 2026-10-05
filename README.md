Logback log appender for AWS CloudWatch
=======================================

# Background

This package provides a logback appender that writes its log events to Cloudwatch.

Before you say it, there seem to be many projects like this out there but I could find none of them that were
self-contained, using the latest logback version and the new AWS SDK 2.x, and that were published to the central
Maven repo.  This project is a fork of the great [work](https://github.com/j256/cloudwatch-logback-appender) done by Gray Watson.

* Code available from the [git repository](https://github.com/alexdupre/cloudwatch-logback-appender).  ![example workflow](https://github.com/alexdupre/cloudwatch-logback-appender/actions/workflows/test.yml/badge.svg)
* Maven packages are published via [![Maven Central](https://maven-badges.herokuapp.com/maven-central/com.alexdupre/cloudwatch-logback-appender/badge.svg?style=flat-square)](https://maven-badges.herokuapp.com/maven-central/com.alexdupre/cloudwatch-logback-appender/) [![javadoc](https://javadoc.io/badge2/com.alexdupre/cloudwatch-logback-appender/javadoc.svg)](https://javadoc.io/doc/com.alexdupre/cloudwatch-logback-appender)

Enjoy.

# SBT Configuration

``` sbt
// NOTE: change the version to the most recent release version from the repo

libraryDependencies += "com.alexdupre" % "cloudwatch-logback-appender" % "3.2" % Runtime
```

## Dependencies

By default the appender has dependencies on logback (duh) but also the cloudwatchlogs and imds AWS SDK 2.x
packages.  You can add an exclusion for these packages if you want to depend on different versions.

``` sbt
libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.6.5"

libraryDependencies ++= Seq("cloudwatchlogs", "imds").map(service => "software.amazon.awssdk" % service % "2.55.11")
```

**NOTE:** Version 3.2 and later require logback 1.6 or later because logback 1.6 replaced the
`PatternLayout.DEFAULT_CONVERTER_MAP` that `Ec2PatternLayout` relies on.  Stay on version 3.1 if you need to run
against logback 1.5.x.

# logback.xml Configuration

Minimal logback appender configuration:

``` xml
<appender name="CLOUDWATCH" class="com.j256.cloudwatchlogbackappender.CloudWatchAppender">
	<region>us-east-1</region>
	<logGroup>your-log-group-name-here</logGroup>
	<logStream>your-log-stream-name-here</logStream>
	<layout>
		<!-- possible layout pattern -->
		<pattern>[%thread] %level %logger{20} - %msg%n%xThrowable</pattern>
	</layout>
</appender>
```

Cloudwatch allows multiple hosts to write to the same log-stream.  Alternatively,
you can configure the log-stream name with an instance-name suffix or something similar.  The `logStream` name setting
uses the `Ec2PatternLayout` to generate the name, which can also be used to format your log lines.  This allows you to
use the standard `%token` such as `%date` in the name of the log-stream – see the
[logback documentation](http://logback.qos.ch/manual/layouts.html#conversionWord).  The `Ec2PatternLayout` class also
adds support for additional tokens:

| Property | Description |
| -------- | ----------- |
| `instanceName` | Name of the EC2 instance or ID if the name is not available. |
| `instance` | Same as instanceName. |
| `in` | Same as instanceName. |
| `instanceId` | ID of the EC2 instance. |
| `iid` | Same as instanceId. |
| `taskId` | ID of the ECS task, read from the `ECS_CONTAINER_METADATA_URI_V4` container metadata. |
| `uuid` | Random UUID as a string |
| `hostName` | Name of the host from `InetAddress.getLocalHost()`. |
| `host` | Same as hostName. |
| `hostAddress` | IP address of the host from `InetAddress.getLocalHost()`. |
| `address` | Same as hostAddress. |
| `addr` | Same as hostAddress. |
| `systemProperty` | Value of a system-property whose name is set as an {option}.  Ex: %systemProperty{os.version}`. |
| `property` | Same as systemProperty. |
| `prop` | Same as systemProperty. |
| `systemEnviron` | Value of a environmental variable whose name is set as an {option}.  Ex: %systemEnviron{SHELL}`. |
| `environ` | Same as systemEnviron. |
| `env` | Same as systemEnviron. |

For example:

``` xml
	<logGroup>your-log-group-name-here</logGroup>
	<logStream>general-%instance-%date{yyyyMMdd,UTC}-%uuid</logStream>
```

This will generate a log-stream name with the prefix "general-" and then with the instance-name,
date in UTC timezone, and a random UUID.

**NOTE:** The instance-name and instance-id tokens will only work when running on an EC2 instance that
supports the EC2MetadataUtils methods for looking up the information, and the task-id token only when running
under ECS.  You can call `Ec2InstanceNameConverter.setInstanceName(...)`, `Ec2InstanceIdConverter.setInstanceId(...)`,
or `TaskIdConverter.setTaskId(...)` early in your program if you want to set them yourself.  If you are running
outside of EC2 and ECS then set `disableAwsMetadata` to true so that the appender doesn't try to reach the metadata
services at all.

**NOTE:** As of version 3.2 these additional tokens are registered on the `Ec2PatternLayout` instance itself and no
longer on the layout map shared by the whole application.  They are available in the `logStream` name and in any
`<layout class="com.j256.cloudwatchlogbackappender.Ec2PatternLayout">` stanza, but not in an unrelated
`PatternLayout` or `PatternLayoutEncoder` elsewhere in your `logback.xml`.

**NOTE:** `logGroup` must match the regex pattern `[.\-_/#A-Za-z0-9]+`.  `logStream` cannot contain the ':' character
which will be replaced by '_'.

The appender also adds the support for the previous list of % tokens to be expanded on each log line:

``` xml
<appender name="CLOUDWATCH" class="com.j256.cloudwatchlogbackappender.CloudWatchAppender">
	...
	<layout class="com.j256.cloudwatchlogbackappender.Ec2PatternLayout">
		<pattern>\[%instance\] \[%thread\] %level %logger{20} - %msg%n%xThrowable</pattern>
	</layout>
```

Here is the complete list of the appender properties.

| Property | Type | Default | Description |
| -------- | ---- | ------- | ----------- |
| `region` | *string* | none | AWS region needed by CloudWatch API |
| `logGroup` | *string* | none | Log group name |
| `logStream` | *string* | none | Log stream name |
| `accessKeyId` | *string* | none | AWS API access key ID, see AWS Permissions below.<br />  Code will use ```DefaultAWSCredentialsProviderChain``` by default. |
| `secretKey` | *string* | none | AWS API secret key, see AWS Permissions below.<br />  Code will use ```DefaultAWSCredentialsProviderChain``` by default. |
| `maxBatchSize` | *int* | 128 | Maximum number of log events put into CloudWatch in single request. |
| `maxBatchTimeMillis` | *long* | 5000 | Maximum time in milliseconds to collect log events to submit batch. |
| `maxQueueWaitTimeMillis` | *long* | 100 | Maximum time in milliseconds to wait if internal queue is full before using the emergency appender (see below). |
| `initialWaitTimeMillis` | *long* | 0 | Initial wait time before logging messages.  Helps if server needs to configure itself initially. |
| `internalQueueSize` | *int* | 8192 | Size of the internal log event queue. |
| `createLogDests` | *boolean* | true | Create the CloudWatch log and stream if they don't exist. |
| `maxEventMessageSize` | *int* | 256k | Maximum size of event message before it is truncated or sent to emergency appender. |
| `truncateEventMessages` | *boolean* | true | If an event it too large, should the message be truncated.  If false then it will be sent to emergency appender. |
| `copyEvents` | *boolean* | true | Copies the event for logging by the background thread. |
| `printRejectedEvents` | *boolean* | false | Print any rejected events to stderr if the emergency appender doesn't work. |
| `endpointUrl` | *string* | none | Override the CloudWatch endpoint, for example to point at LocalStack.  By default the standard endpoint for the region is used. |
| `retentionDays` | *int* | 0 | Retention period applied to a log-group that this appender creates.  0 keeps the CloudWatch default of never expiring.  An existing log-group is left alone. |
| `waitForAllEvents` | *boolean* | true | On stop, wait for the queued events to be written to CloudWatch instead of quitting immediately. |
| `disableAwsMetadata` | *boolean* | false | Set to true when not running under EC2 or ECS so that the appender doesn't try to download the instance and task metadata.  Values you set yourself with the converter setters are left alone. |

## Emergency Appender

Since this appender is queuing up log events and then writing them remotely, there are a number of situations which
might result in log events not getting remoted correctly.  To protect against this, you can add in an "emergency"
appender to write events to the console or a file by adding the following to your CLOUDWATCH appender stanza:

``` xml
<appender name="CLOUDWATCH" class="com.j256.cloudwatchlogbackappender.CloudWatchAppender">
	...
	<appender-ref ref="EMERGENCY_FILE" />
```

This appender will be used if:

* there was some problem configuring the CloudWatch or other AWS APIs
* the internal queue fills up and messages can't be written remotely fast enough
* there was some problem with the actual put events CloudWatch call – maybe a transient network failure

If no emergency appender is configured and a problem does happen then the log messages will be not be persisted.

# AWS Permissions

You can specify the AWS CloudWatch permissions in a number of ways.  If you use the `accessKeyId` and `secretKey`
settings in the `logback.xml` file then the appender will use those credentials directly.  You can also set the
`cloudwatchappender.aws.accessKeyId` and `cloudwatchappender.aws.secretKey` Java System properties which will be
used.  If neither of those are specified then the appender will use the `DefaultAWSCredentialsProviderChain` which
looks for the access and secret keys in:

* Environment Variables: `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY` or `AWS_ACCESS_KEY` and `AWS_SECRET_KEY`
* Java System Properties: `aws.accessKeyId` and `aws.secretKey`
* Credential file at the default location (`~/.aws/credentials`) shared by all AWS SDKs and the AWS CLI
* Instance profile credentials delivered through the Amazon EC2 metadata service

## IAM Permissions

When making any AWS API calls, we typically create a IAM user with specific permissions so if any API keys are stolen,
the hacker only have limited access to our AWS services.  To get the appender to be able to publish to CloudWatch,
the following IAM policy is required to create the log group and put log events to CloudWatch.

The `logs:CreateLogGroup` and `logs:CreateLogStream` actions are only required if the appender is creating the
log-group and stream itself (see `createLogDests` option above), and `logs:PutRetentionPolicy` only if you also set
the `retentionDays` option.

```json
{
    "Version": "2012-10-17",
    "Statement": [
        {
            "Effect": "Allow",
            "Action": [
                "logs:CreateLogGroup",
                "logs:CreateLogStream",
                "logs:DescribeLogGroups",
                "logs:DescribeLogStreams",
                "logs:PutLogEvents"
            ],
            "Resource": [
                "*"
            ]
        }
    ]
}
```

To restrict access to a specific and existing log group you should use a policy like this:

```json
{
    "Version": "2012-10-17",
    "Statement": [
        {
            "Effect": "Allow",
            "Action": [
                "logs:CreateLogStream",
                "logs:DescribeLogStreams",
                "logs:PutLogEvents"
            ],
            "Resource": [
                "arn:aws:logs:::log-group:test",
                "arn:aws:logs:::log-group:test:log-stream:*"
            ]
        },
        {
            "Effect": "Allow",
            "Action": [
                "logs:DescribeLogGroups"
            ],
            "Resource": "*"
        }
    ]
}
```

## EC2 Permissions

If you want the appender to query for the ec2 instance name it is on (see `Ec2PatternLayout` above), then you need to
[allow access to tags in instance metadata](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/Using_Tags.html#allow-access-to-tags-in-IMDS).

# ChangeLog Release Notes

See the [CHANGES.txt](CHANGES.txt) file.
