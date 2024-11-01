package com.confluent.csta;

import org.apache.kafka.common.errors.PolicyViolationException;
import org.apache.kafka.server.policy.CreateTopicPolicy;

import java.util.Map;

public class MyTopicPolicy implements CreateTopicPolicy {
    @Override
    public void validate(RequestMetadata requestMetadata) throws PolicyViolationException {
        if (requestMetadata.topic() != null && ! requestMetadata.topic().startsWith("_") && ! requestMetadata.topic().startsWith("connect-")) {
            if (! requestMetadata.topic().startsWith("demo")) {
                throw new PolicyViolationException("Topic name should start with demo, received:" + requestMetadata.topic());
            }
            /*if( requestMetadata.numPartitions() > 4 ) {
                throw new PolicyViolationException("Number of partitions should not be greater than 4, received:" + requestMetadata.numPartitions());
            }*/
        }
        
    }

    @Override
    public void close() throws Exception {

    }

    @Override
    public void configure(Map<String, ?> configs) {

    }
}