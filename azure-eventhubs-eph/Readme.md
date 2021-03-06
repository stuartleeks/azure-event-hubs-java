# Consuming Events with the Java Event Processor Host for Azure Event Hubs

Event Processor Host is built on top of the Java client for Azure Event Hubs and provides a number of features
not present in that lower layer:

1. Event Processor Host removes the need to write a receive loop. The user simply creates a Java class which
   implements the IEventProcessor interface, and Event Processor Host will call an instance of that class when
   events are available.
2. Event Processor Host removes the need to think about partitions. By default, it creates as many instances of the event
   processor class as are required to receive events from all partitions. Each instance will only ever handle
   events from one partition, further simplifying the processing code. If you need a different pattern, you can
   replace the event processor factory and generate and dispense event processor instances in any way you like.
3. Event Processor Host allows easy load balancing. Utilizing a shared persistent store for leases on partitions
   (by default based on Azure Storage), instances of Event Processor Host receiving from the same consumer group
   of the same Event Hub can be spread across multiple machines and partitions will be distributed across those
   machines as evenly as possible. These instances can be started and stopped at any time, and partitions will be
   redistributed as needed. It is even allowed to have more instances than partitions as a form of hot standby. (Note that
   partition distribution is based solely on the number of partitions per instance, not event flow rate or any other metric.)
4. Event Processor Host allows the event processor to create a persistent "checkpoint" that describes a position in
   the partition's event stream, and if restarted it automatically begins receiving at the next event after the checkpoint.
   Because checkpointing is usually an expensive operation, it is up to the user's event processor code to create
   them, at whatever interval is suitable for the user's application. For example, an application with relatively
   infrequent messages might checkpoint after processing each one, whereas an application that requires high performance in
   the processing code in order to keep up with event flow might checkpoint once every hundred messages, or once
   per second, etc.

## Getting Started

This library is available from the Maven Central Repository. See the readme for the Java Azure Event Hubs client for more information.

## Using Event Processor Host

### Step 1: Implement IEventProcessor

There are four methods which need to be implemented: onOpen, onClose, onError, and onEvents.
onOpen and onClose are called when an event processor instance is created and shut down, respectively, and are intended for setup
and cleanup. For example, in onOpen the user might open a database connection, and then close it in onClose. onError is called when
an error tied to the partition, such as a receiver failure, has occurred. Recovering from the error, if possible, is up to
Event Processor Host; the call to onError is primarily informational. If it is not possible to recover from the error and the event
processor instance must be shut down, onClose will be called to allow graceful cleanup.

The onEvents method is where the real work of processing
events occurs: whenever additional events become available for the partition, this method will be called with a batch of events.
The maximum number of events in a batch can be controlled by an option when the event processor class is registered, described below,
and defaults to 10; the actual number of events in a particular batch will vary between 1 and the specified maximum. onEvents may also
be called with an empty iterable on receive timeout, if an option is set when the event processor class is registered, but by default will not.
Note that if onEvents throws an exception out to the calling code before processing all events in the iterable, it loses the opportunity to
process the remaining events. We strongly recommend having a try-catch inside the loop which iterates over the events.

By default, any particular instance of the event processor is permanently associated with a partition. A PartitionContext
object is provided to every call, but the partition id will never change from call to call. If you are using a non-default event processor
factory to implement a different pattern, such as one where an event processor instance can handle events from multiple partitions,
then the PartitionContext becomes more meaningful.

PartitionContext also provides the means to create a checkpoint for the partition. The code snippet below checkpoints after
processing every event, for the purpose of providing an example. Because checkpointing is usually an expensive operation, this
pattern is not appropriate for every application.

``` Java
class EventProcessor implements IEventProcessor
{
  @Override
  public void onOpen(PartitionContext context) throws Exception
  {
       System.out.println("Partition " + context.getPartitionId() + " is opening");
  }

  @Override
  public void onClose(PartitionContext context, CloseReason reason) throws Exception
  {
      System.out.println("Partition " + context.getPartitionId() + " is closing for reason " + reason.toString());
  }

  @Override
  public void onError(PartitionContext context, Throwable error)
  {
      System.out.println("Partition " + context.getPartitionId() + " got error " + error.toString());
  }

  @Override
  public void onEvents(PartitionContext context, Iterable<EventData> events) throws Exception
  {
      System.out.println("SAMPLE: Partition " + context.getPartitionId() + " got message batch");
      for (EventData data : events)
      {
          try
          {
              // Do something useful with the event here.
          }
          catch (Exception e) // Replace with specific exceptions to catch.
          {
              // Handle the message-specific issue, or at least swallow the exception so the
              // loop can go on to process the next event. Throwing out of onEvents results in
              // skipping the entire rest of the batch.
          }

          context.checkpoint(data);
      }
  }
}
```

### Step 2: Implement the General Error Notification Handler

This is a class which implements Consumer<ExceptionReceivedEventArgs>. There is just one required method, accept, which will be
called with an argument of type ExceptionReceivedEventArgs if an error occurs which is not tied to any particular event processor. The
ExceptionReceivedEventArgs contains information specifying the instance of EventProcessorHost where the error occurred, the
exception, and the action being performed at the time of the error. To install this handler, an object of this class is passed
as an option when the event processor class is registered. Recovering from the error, if possible, is up to Event Processor Host; this
notification is primarily informational.

``` Java
class ErrorNotificationHandler implements Consumer<ExceptionReceivedEventArgs>
{
  @Override
  public void accept(ExceptionReceivedEventArgs t)
  {
      // Handle the notification here
  }
}
```

### Step 3: Instantiate EventProcessorHost

In order to do this, the user will first need to build a connection string for the Event Hub. This may be conveniently done using
the ConnectionStringBuilder class provided by the Java client for Azure Event Hubs. Make sure the sasKey has listen permission.

The EventProcessorHost class itself has multiple constructors. All of them require the path to the Event Hub, the name of the consumer
group to receive from, and the connection string for the Event Hub. The most basic constructor also requires an Azure Storage
connection string for a storage account that the built-in partition lease and checkpoint managers will use to persist these
artifacts, and the name of a container to user or create in that storage account. Other constructors add more options. The
most advanced constructor allows the user to replace the Azure Storage-based lease and checkpoint managers with user implementations
of ILeaseManager and ICheckpointManager (for example, to use Zookeeper instead of Azure Storage).

    ``` Java
    ConnectionStringBuilder eventHubConnectionString = new ConnectionStringBuilder(namespaceName, eventHubName, sasKeyName, sasKey);
    EventProcessorHost host = new EventProcessorHost(eventHubName, consumerGroupName, eventHubConnectionString.toString(), storageConnectionString, storageContainerName);
    ```

### Step 4: Register the Event Processor Implementation to Start Processing Events

Instantiate an object of class EventProcessorOptions and call the setExceptionNotification method with an object of the class
implemented in step 2. This is also the time to modify the maximum event batch size (setMaxBatchSize), or set other options
such as the receive timeout duration or prefetch count.

To start processing events, call registerEventProcessor with the options object and the .class of the IEventProcessor implementation
from step 1. This call returns a Future which will complete when initialization is finished and event pumping is about to begin.
Waiting for the Future to complete (by calling get) is important because initialization failures are detected by catching
ExecutionException from the get call. The actual failure is available as the inner exception on the ExecutionException.

The code shown here uses the default event processor factory, which will generate and dispense a new instance of the event processor class
for every partition. To use a different pattern, you would need to implement IEventProcessorFactory and pass an instance of the
implementation to EventProcessorHost.registerEventProcessorFactory. 

``` Java
EventProcessorOptions options = EventProcessorOptions.getDefaultOptions();
options.setExceptionNotification(new ErrorNotificationHandler());
try
{
  host.registerEventProcessor(EventProcessor.class, options).get();
}
catch (Exception e)
{
  System.out.print("Failure while registering: ");
  if (e instanceof ExecutionException)
  {
      Throwable inner = e.getCause();
      System.out.println(inner.toString());
  }
  else
  {
      System.out.println(e.toString());
  }
}
```

### Step 5: Graceful Shutdown

When the time comes to shut down the instance of EventProcessorHost, call the unregisterEventProcessor method.

 ``` Java
 host.unregisterEventProcessor();
 ```

If the entire process is shutting down and will never create a new instance of EventProcessorHost, then it is also time to shut
down EventProcessorHost's internal thread pool. (This can also be done automatically, but the automatic option should only be
used if there is no possibility of creating a new EventProcessorHost after all existing instances have been shut down. The
manual method is safer and hence is the default.) The EventProcessorHost.forceExecutorShutdown method takes one argument, the
number of seconds to wait for all threads in the pool to exit. After calling unregisterEventProcessor on all instances of
EventProcessorHost, all threads in the pool should have exited anyway, so the timeout can be relatively short. The 120 seconds
shown here is very, very conservative.

``` Java
EventProcessorHost.forceExecutorShutdown(120);
```

## Threading Notes

Calls to the IEventProcessor methods onOpen, onEvents, and onClose are serialized for a given partition. There is no guarantee that
calls to these methods will be on any particular thread, but there will only be one call to any of these methods at a time. The onError
method does not share this guarantee. In particular, if onEvents throws an exception up to the caller, then onError will be called with
that exception. Technically onError is not running at the same time as onEvents, since onEvents has terminated by throwing, but shared data
may be in an unexpected state.

When using the default event processor factory, there is one IEventProcessor instance per partition, and each instance is permanently tied
to one partition. Under these conditions, an IEventProcessor instance is effectively single-threaded, except for onError. A user-supplied
event processor factory can implement any pattern, such as creating only one IEventProcessor instance and dispensing that instance for use
by every partition. In that example, onEvents will not receive multiple calls for any given partition at the same time, but it can be called
on multiple threads for different partitions.

## Running Tests

Event Processor Host comes with a suite of JUnit-based tests. To run these tests, you will need an event hub and an Azure Storage account.
You can create both through the Azure Portal at [portal.azure.com](http://portal.azure.com/). Once you have done that, get the
connection strings for both and place them in environment variables:

* `EVENT_HUB_CONNECTION_STRING` is the event hub connection string. The connection string needs to include a SAS rule which has send and listen permissions.
* `EPHTESTSTORAGE` is the storage account connection string.

Under src/test/java, the general test cases are in files named *Test. If you have made modifications to the code, these are the
cases to run in order to detect major breakage. There are also some test cases in Repros.java, but those are not suitable for
general use. That file preserves repro code from times when we had to mount a major investigation to get to the
bottom of a problem.

## Tracing

Event Processor Host can trace its execution for debugging and problem diagnosis, using the standard Java java.util.logging facilities.
So can the underlying Java Azure Event Hubs client, and the Apache Qpid Proton-J AMQP client underneath that.

Right now, the only way to start tracing is by restarting the JVM with a -D parameter that points to a logging configuration file.
This is not optimal for a variety of reasons. For example, Event Processor Host is generally used in long-running applications and
restarting may cause unacceptable service disruption. The customer may or may not even have the ability to control the arguments
passed to the JVM, depending on their environment. And if the intent is to capture traces relating to an observed problem, restarting
may cause the problem to go away. I have opened [issue 85 in our GitHub repro](https://github.com/Azure/azure-event-hubs-java/issues/85)
to track this. If you have any thoughts or ideas on the subject, please comment there!

To start tracing, run the JVM with the additional argument -Djava.util.logging.config.file=yourconfigfile, where yourconfigfile is a text
file with the following contents:

 ```
 # FileHandler traces to a file
 handlers=java.util.logging.FileHandler
 # Turn tracing off by default to avoid a potential tidal wave of undesired traces
 .level=OFF
 # Set tracing level for Event Processor Host as desired
 eventprocessorhost.trace.level=FINE
 # Set tracing level for Event Hub client as desired
 servicebus.trace.level=FINE
 # Set tracing for Apache Qpid Proton-J as desired
 proton.trace.level=FINE
 # Tell FileHandler to not filter anything
 java.util.logging.FileHandler.level=ALL
 # TODO Set the name of the output file
 java.util.logging.FileHandler.pattern=tracefilename
 # Use the SimpleFormatter and specify the particular format
 java.util.logging.SimpleFormatter.format=[%1$tF %1$tr] %3$s %4$s: %5$s %n
 java.util.logging.FileHandler.formatter=java.util.logging.SimpleFormatter
 ```
