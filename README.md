# CFK K8s Gate Keeper

- [CFK K8s Gate Keeper](#cfk-k8s-gate-keeper)
  - [Intro](#intro)
  - [Setup](#setup)
  - [Create an OPA Gatekeeper for topic names](#create-an-opa-gatekeeper-for-topic-names)
  - [Create an OPA Gatekeeper for schema subject names](#create-an-opa-gatekeeper-for-schema-subject-names)
    - [Schema Regisry Setup](#schema-regisry-setup)
    - [Create the OPA Gatekeeper for subject names](#create-the-opa-gatekeeper-for-subject-names)
  - [Define the policy at Kafka level](#define-the-policy-at-kafka-level)
  - [Cleanup](#cleanup)

## Intro

Nowadays within CFK there's no way to create a policy for topic and schema names.

An option would be to build at K8s level a gate keeper to setup a policy for allowed names of topics and schema subject names. Here we display how to do it for topic names and schema subject names leveraging https://open-policy-agent.github.io/gatekeeper/website/.

Note that this is not enforced at the level of the Kafka brokers themselves that would still accept whatever name you specify through the Kafka protocol. For controlling at the broker level we have though: 
- https://cwiki.apache.org/confluence/display/KAFKA/KIP-108%3A+Create+Topic+Policy

Here we also display this option which could be coupled together with K8s gatekeeper in a CFK deployment scenario.

## Setup

For quick setup:

```shell
kind create cluster
kubectl create namespace confluent
kubectl config set-context --current --namespace=confluent
helm repo add confluentinc https://packages.confluent.io/helm
helm upgrade --install operator confluentinc/confluent-for-kubernetes --namespace confluent
```

For installing dashboard:

```shell
helm repo add kubernetes-dashboard https://kubernetes.github.io/dashboard/
helm upgrade --install kubernetes-dashboard kubernetes-dashboard/kubernetes-dashboard --create-namespace --namespace kubernetes-dashboard
```

To create token:

```shell
k  create serviceaccount -n kubernetes-dashboard admin-user
k create clusterrolebinding -n kubernetes-dashboard admin-user --clusterrole cluster-admin --serviceaccount=kubernetes-dashboard:admin-user
token=$(kubectl -n kubernetes-dashboard create token admin-user)
echo $token
```

Wait for pods to be ready:

```shell
k get pods -n kubernetes-dashboard
```

To access dashboard:

```shell
kubectl -n kubernetes-dashboard port-forward svc/kubernetes-dashboard-kong-proxy 8443:443
```

Check the operator pod has started:

```shell
kubectl get pods --namespace confluent
```

Once the operator pod is ready we install our Confluent KRaft broker:

```shell
kubectl apply -f kraft.yaml
```

And wait for all pods (kraft and kafka) to be ready:

```shell
kubectl get pods --namespace confluent
```

Check topics listed include `demotopic`:

```shell
kubectl exec kafka-0 -- kafka-topics --bootstrap-server localhost:9092 --list
```

## Create an OPA Gatekeeper for topic names

Let's install the OPA Gatekeeper:

```shell
helm repo add gatekeeper https://open-policy-agent.github.io/gatekeeper/charts
helm install gatekeeper/gatekeeper --name-template=gatekeeper --namespace gatekeeper-system --create-namespace
```

Confirm pods are ready:

```shell
kubectl get pods --namespace gatekeeper-system
```

Now lets's install a template that demands all topic names to be composed of small letters only `^[a-z]+$`:

```shell
kubectl apply -f kafkatopic-naming-template.yaml
```

Now we create a constraint that applies to `KafkaTopic` resources:

```shell
kubectl apply -f kafkatopic-naming-constraint.yaml
```

Now we test with an invalid topic name `wrongtopic2024`:

```shell
kubectl apply -f wrong-topic.yaml
```

Now we test with a valid topic name `righttopic`:

```shell
kubectl apply -f right-topic.yaml
```

We can list again our topics:

```shell
kubectl exec kafka-0 -- kafka-topics --bootstrap-server localhost:9092 --list
```

## Create an OPA Gatekeeper for schema subject names

### Schema Regisry Setup

First let's deploy the Schema Registry:

```shell
kubectl apply -f schema-registry.yaml
```

Check the pods are ready:

```shell
kubectl get pods --namespace confluent
```

We create a schema config first:

```shell
kubectl apply -f demo-schema.yaml
```

Next we apply to the value of the originally creatde topic `demotopic`:

```shell
kubectl apply -f demotopic-value-schema.yaml
```

Now we can check the schema for our topic:

```shell
kubectl exec schemaregistry-0 -- curl -s http://localhost:8081/subjects/demotopic-value/versions/latest | jq '.schema|fromjson[]'
```

### Create the OPA Gatekeeper for subject names

Now lets's install the template that demands all subject names to be composed of small letters followed by `-value`:

```shell
kubectl apply -f subject-naming-template.yaml
```

Now we create a constraint that applies to `Schema` resources:

```shell
kubectl apply -f subject-naming-constraint.yaml
```

Now we test with an invalid subject name `righttopic-key`:

```shell
kubectl apply -f righttopic-key-schema.yaml
```

Now with a valid subject name `righttopic-value`:

```shell
kubectl apply -f righttopic-value-schema.yaml
```

And checking the schema:

```shell
kubectl exec schemaregistry-0 -- curl -s http://localhost:8081/subjects/righttopic-value/versions/latest | jq '.schema|fromjson[]'
```

You can also list the schemas:

```shell
kubectl exec schemaregistry-0 -- curl -s http://localhost:8081/subjects/
```

## Define the policy at Kafka level

First let's clean up our environment:

```shell
kind delete cluster 
```

And reinstall our cluster:

```shell
kind create cluster
kubectl create namespace confluent
kubectl config set-context --current --namespace=confluent
helm repo add confluentinc https://packages.confluent.io/helm
helm upgrade --install operator confluentinc/confluent-for-kubernetes --namespace confluent
```

Dashboard:

```shell
helm repo add kubernetes-dashboard https://kubernetes.github.io/dashboard/
helm upgrade --install kubernetes-dashboard kubernetes-dashboard/kubernetes-dashboard --create-namespace --namespace kubernetes-dashboard
```

Token:

```shell
k  create serviceaccount -n kubernetes-dashboard admin-user
k create clusterrolebinding -n kubernetes-dashboard admin-user --clusterrole cluster-admin --serviceaccount=kubernetes-dashboard:admin-user
token=$(kubectl -n kubernetes-dashboard create token admin-user)
echo $token
```

Wait for pods to be ready:

```shell
k get pods -n kubernetes-dashboard
```

And dashboard access:

```shell
kubectl -n kubernetes-dashboard port-forward svc/kubernetes-dashboard-kong-proxy 8443:443
```

Check the operator pod has started:

```shell
kubectl get pods --namespace confluent
```

And meanwhile compile our custom library:

```shell
cd create-topic-policy
mvn clean package
cd ..
```

So first we build our image:

```shell
cd create-topic-policy
docker build -t my-repo/custom-kafka-broker:7.7.0 .
cd ..
```

Load in kind:

```shell
kind load docker-image my-repo/custom-kafka-broker:7.7.0
```

Now we can deploy the KRaft based cluster with our custom policy configured:

```shell
kubectl apply -f kraf-custom-policy.yaml
```

Check pods are ready:

```shell
kubectl get pods --namespace confluent
```

We check our topics (after some time our initial `demotopic` should show up):

```shell
kubectl exec kafka-0 -- kafka-topics --bootstrap-server localhost:9092 --list
```

And try to create a new one named `righttopic`:

```shell
kubectl apply -f right-topic.yaml
```

Although CFK may respond fine if we check it shouldnt be there:

```shell
kubectl exec kafka-0 -- kafka-topics --bootstrap-server localhost:9092 --list
```

And if you execute you should be able to see on logs the error:

```shell
k logs kafka-0 | grep 'Topic name should start with demo, received:'
```

It shouldn't be possible cause our policy demands to start with `demo`. But if we try:

```shell
kubectl apply -f demoright-topic.yaml
```

And if we tried directly the error now should be explicit:

```shell
kubectl exec kafka-0 -- kafka-topics --bootstrap-server localhost:9092 --topic righttopic2 --create --partitions 3 --replication-factor 1
```

But if we try:

```shell
kubectl apply -f demoright-topic.yaml
```

The topic should ion fact be created cause its name starts with demo:

```shell
kubectl exec kafka-0 -- kafka-topics --bootstrap-server localhost:9092 --list
```

So ideally in a CFK scenario one would say the best thing could be to have both combined: the `create.topic.policy.class.name` and the K8s OPA Gatekeeper.

## Cleanup

```shell
kind delete cluster 
```