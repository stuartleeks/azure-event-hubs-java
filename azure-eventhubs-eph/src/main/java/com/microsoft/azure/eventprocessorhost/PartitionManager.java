/*
 * Copyright (c) Microsoft. All rights reserved.
 * Licensed under the MIT license. See LICENSE file in the project root for full license information.
 */

package com.microsoft.azure.eventprocessorhost;

import java.util.Map;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.logging.Level;

import com.microsoft.azure.eventhubs.EventHubClient;
import com.microsoft.azure.eventhubs.EventHubRuntimeInformation;
import com.microsoft.azure.servicebus.IllegalEntityException;
import com.microsoft.azure.servicebus.ServiceBusException;
import com.microsoft.azure.servicebus.TimeoutException;

class PartitionManager
{
	// Protected instead of private for testability
    protected final EventProcessorHost host;
    protected Pump pump;

    private String partitionIds[] = null;
    
    private Future<?> partitionsFuture = null;
    private boolean keepGoing = true;

    PartitionManager(EventProcessorHost host)
    {
        this.host = host;
    }
    
    String[] getPartitionIds() throws IllegalEntityException
    {
        Throwable saved = null;

        if (this.partitionIds == null)
        {
			try
			{
				EventHubClient ehClient = EventHubClient.createFromConnectionStringSync(this.host.getEventHubConnectionString());
				EventHubRuntimeInformation ehInfo = ehClient.getRuntimeInformation().get();
				if (ehInfo != null)
				{
					this.partitionIds = ehInfo.getPartitionIds();
	
					this.host.logWithHost(Level.FINE, "Eventhub " + this.host.getEventHubPath() + " count of partitions: " + ehInfo.getPartitionCount());
					for (String id : this.partitionIds)
					{
						this.host.logWithHost(Level.FINER, "Found partition with id: " + id);
					}
				}
				else
				{
					saved = new TimeoutException("getRuntimeInformation returned null");
				}
			}
			catch (ServiceBusException | IOException | InterruptedException | ExecutionException e)
			{
				saved = e;
			}
        }
        if (this.partitionIds == null)
        {
			throw new IllegalEntityException("Failure getting partition ids for event hub", saved);
        }
        
        return this.partitionIds;
    }

    // Testability hook: allows a test subclass to insert dummy pump.
    Pump createPumpTestHook()
    {
        return new Pump(this.host);
    }

    // Testability hook: called after stores are initialized.
    void onInitializeCompleteTestHook()
    {
    }

    // Testability hook: called at the end of the main loop after all partition checks/stealing is complete.
    void onPartitionCheckCompleteTestHook()
    {
    }
    
    Future<?> stopPartitions()
    {
    	this.keepGoing = false;
    	return this.partitionsFuture;
    }
    
    // Return Void so it can be called from a lambda.
    public Void initialize() throws Exception
    {
    	this.pump = createPumpTestHook();
    	
    	try
    	{
    		initializeStores();
    		onInitializeCompleteTestHook();
    	}
    	catch (ExceptionWithAction e)
    	{
    		this.host.logWithHost(Level.SEVERE, "Exception while initializing stores (" + e.getAction() + "), not starting partition manager", e.getCause());
    		throw e;
    	}
    	catch (Exception e)
    	{
    		this.host.logWithHost(Level.SEVERE, "Exception while initializing stores, not starting partition manager", e);
    		throw e;
    	}
    	
    	this.partitionsFuture = EventProcessorHost.getExecutorService().submit(() -> runAndCleanUp());
    	
    	return null;
    }
    
    // Return Void so it can be called from a lambda.
    private Void runAndCleanUp()
	{
    	try
    	{
    		runLoop();
    		this.host.logWithHost(Level.FINE, "Partition manager main loop exited normally, shutting down");
    	}
    	catch (ExceptionWithAction e)
    	{
    		this.host.logWithHost(Level.SEVERE, "Exception from partition manager main loop, shutting down", e.getCause());
    		this.host.getEventProcessorOptions().notifyOfException(this.host.getHostName(), e, e.getAction());
    	}
    	catch (Exception e)
    	{
    		if ((e instanceof ExecutionException) && (e.getCause() instanceof OutOfMemoryError))
    		{
    			Exception forLogging = new Exception("Got OutOfMemoryError with " + Thread.activeCount() + " threads running");
    			this.host.getEventProcessorOptions().notifyOfException(this.host.getHostName(), forLogging, EventProcessorHostActionStrings.PARTITION_MANAGER_MAIN_LOOP);
    			Map<Thread, StackTraceElement[]> stacks = Thread.getAllStackTraces();
    			for (Map.Entry<Thread, StackTraceElement[]> entry : stacks.entrySet())
    			{
    				String stackString = "Thread " + entry.getKey().getId() + ":\n";
    				for (int i = 0; i < entry.getValue().length; i++)
    				{
    					stackString += (entry.getValue()[i].toString() + "\n");
    				}
    				forLogging = new Exception(stackString);
        			this.host.getEventProcessorOptions().notifyOfException(this.host.getHostName(), forLogging, EventProcessorHostActionStrings.PARTITION_MANAGER_MAIN_LOOP);
    			}
    		}
    		this.host.logWithHost(Level.SEVERE, "Exception from partition manager main loop, shutting down", e);
    		this.host.getEventProcessorOptions().notifyOfException(this.host.getHostName(), e, EventProcessorHostActionStrings.PARTITION_MANAGER_MAIN_LOOP);
    	}
    	
    	// Cleanup
    	this.host.logWithHost(Level.FINE, "Shutting down all pumps");
    	Iterable<Future<?>> pumpRemovals = this.pump.removeAllPumps(CloseReason.Shutdown);
    	
    	// All of the shutdown threads have been launched, we can shut down the executor now.
    	// Shutting down the executor only prevents new tasks from being submitted.
    	// We can't wait for executor termination here because this thread is in the executor.
    	this.host.stopExecutor();
    	
    	// Wait for shutdown threads.
    	for (Future<?> removal : pumpRemovals)
    	{
    		try
    		{
				removal.get();
			}
    		catch (InterruptedException | ExecutionException e)
    		{
    			this.host.logWithHost(Level.SEVERE, "Failure during shutdown", e);
    			this.host.getEventProcessorOptions().notifyOfException(this.host.getHostName(), e, EventProcessorHostActionStrings.PARTITION_MANAGER_CLEANUP);
    			
    			// By convention, bail immediately on interrupt, even though we're just cleaning
    			// up on the way out. Fortunately, we ARE just cleaning up on the way out, so we're
    			// free to bail without serious side effects.
    			if (e instanceof InterruptedException)
    			{
    				Thread.currentThread().interrupt();
    				throw new RuntimeException(e);
    			}
			}
    	}
    	
    	this.host.logWithHost(Level.FINE, "Partition manager exiting");
    	
    	return null;
    }
    
    private void initializeStores() throws InterruptedException, ExecutionException, ExceptionWithAction, IllegalEntityException
    {
        ILeaseManager leaseManager = this.host.getLeaseManager();
        
        // Make sure the lease store exists
        if (!leaseManager.leaseStoreExists().get())
        {
        	retryWrapper(() -> leaseManager.createLeaseStoreIfNotExists(), null, "Failure creating lease store for this Event Hub, retrying",
        			"Out of retries creating lease store for this Event Hub", EventProcessorHostActionStrings.CREATING_LEASE_STORE, 5);
        }
        // else
        //	lease store already exists, no work needed
        
        // Now make sure the leases exist
        for (String id : getPartitionIds())
        {
        	retryWrapper(() -> leaseManager.createLeaseIfNotExists(id), id, "Failure creating lease for partition, retrying",
        			"Out of retries creating lease for partition", EventProcessorHostActionStrings.CREATING_LEASE, 5);
        }
        
        ICheckpointManager checkpointManager = this.host.getCheckpointManager();
        
        // Make sure the checkpoint store exists
        if (!checkpointManager.checkpointStoreExists().get())
        {
        	retryWrapper(() -> checkpointManager.createCheckpointStoreIfNotExists(), null, "Failure creating checkpoint store for this Event Hub, retrying",
        			"Out of retries creating checkpoint store for this Event Hub", EventProcessorHostActionStrings.CREATING_CHECKPOINT_STORE, 5);
        }
        // else
        //	checkpoint store already exists, no work needed
        
        // Now make sure the checkpoints exist
        for (String id : getPartitionIds())
        {
        	retryWrapper(() -> checkpointManager.createCheckpointIfNotExists(id), id, "Failure creating checkpoint for partition, retrying",
        			"Out of retries creating checkpoint blob for partition", EventProcessorHostActionStrings.CREATING_CHECKPOINT, 5);
        }
    }
    
    // Throws if it runs out of retries. If it returns, action succeeded.
    private void retryWrapper(Callable<Future<?>> lambda, String partitionId, String retryMessage, String finalFailureMessage, String action, int maxRetries) throws ExceptionWithAction
    {
    	boolean createdOK = false;
    	int retryCount = 0;
    	do
    	{
    		try
    		{
    			lambda.call().get();
    			createdOK = true;
    		}
    		catch (Exception e)
    		{
    			if (partitionId != null)
    			{
    				this.host.logWithHostAndPartition(Level.WARNING, partitionId, retryMessage, e);
    			}
    			else
    			{
    				this.host.logWithHost(Level.WARNING, retryMessage, e);
    			}
    			retryCount++;
    		}
    	} while (!createdOK && (retryCount < maxRetries));
    	if (!createdOK)
        {
    		if (partitionId != null)
    		{
    			this.host.logWithHostAndPartition(Level.SEVERE, partitionId, finalFailureMessage);
    		}
    		else
    		{
    			this.host.logWithHost(Level.SEVERE, finalFailureMessage);
    		}
    		throw new ExceptionWithAction(new RuntimeException(finalFailureMessage), action);
        }
    }
    
    private void runLoop() throws Exception, ExceptionWithAction
    {
    	while (this.keepGoing)
    	{
            ILeaseManager leaseManager = this.host.getLeaseManager();
            HashMap<String, Lease> allLeases = new HashMap<String, Lease>();

            // Inspect all leases.
            // Acquire any expired leases.
            // Renew any leases that currently belong to us.
            Iterable<Future<Lease>> gettingAllLeases = leaseManager.getAllLeases();
            ArrayList<Lease> leasesOwnedByOthers = new ArrayList<Lease>();
            int ourLeasesCount = 0;
            for (Future<Lease> leaseFuture : gettingAllLeases)
            {
            	Lease possibleLease = null;
            	try
            	{
                    possibleLease = leaseFuture.get();
                    if (possibleLease.isExpired())
                    {
                    	if (leaseManager.acquireLease(possibleLease).get())
                    	{
                    		allLeases.put(possibleLease.getPartitionId(), possibleLease);
                    		ourLeasesCount++;
                    	}
                    	else
                    	{
                    		// Probably failed because another host stole it between get and acquire
                        	allLeases.put(possibleLease.getPartitionId(), possibleLease);
                        	leasesOwnedByOthers.add(possibleLease);
                    	}
                    }
                    else if (possibleLease.getOwner().compareTo(this.host.getHostName()) == 0)
                    {
                        if (leaseManager.renewLease(possibleLease).get())
                        {
                            allLeases.put(possibleLease.getPartitionId(), possibleLease);
                            ourLeasesCount++;
                        }
                    	else
                    	{
                    		// Probably failed because another host stole it between get and renew
                        	allLeases.put(possibleLease.getPartitionId(), possibleLease);
                        	leasesOwnedByOthers.add(possibleLease);
                    	}
                    }
                    else
                    {
                    	allLeases.put(possibleLease.getPartitionId(), possibleLease);
                    	leasesOwnedByOthers.add(possibleLease);
                    }
            	}
            	catch (ExecutionException e)
            	{
            		this.host.logWithHost(Level.WARNING, "Failure getting/acquiring/renewing lease, skipping", e);
            		this.host.getEventProcessorOptions().notifyOfException(this.host.getHostName(), e, EventProcessorHostActionStrings.CHECKING_LEASES,
            				((possibleLease != null) ? possibleLease.getPartitionId() : ExceptionReceivedEventArgs.NO_ASSOCIATED_PARTITION));
            	}
            }
            
            // Grab more leases if available and needed for load balancing
            if (leasesOwnedByOthers.size() > 0)
            {
	            Iterable<Lease> stealTheseLeases = whichLeasesToSteal(leasesOwnedByOthers, ourLeasesCount);
	            if (stealTheseLeases != null)
	            {
	            	for (Lease stealee : stealTheseLeases)
	            	{
	            		try
	            		{
    	                	if (leaseManager.acquireLease(stealee).get())
    	                	{
    	                		this.host.logWithHostAndPartition(Level.FINE, stealee.getPartitionId(), "Stole lease");
    	                		allLeases.put(stealee.getPartitionId(), stealee);
    	                		ourLeasesCount++;
    	                	}
    	                	else
    	                	{
    	                		this.host.logWithHost(Level.WARNING, "Failed to steal lease for partition " + stealee.getPartitionId());
    	                	}
	            		}
	            		catch (ExecutionException e)
	            		{
	            			this.host.logWithHost(Level.SEVERE, "Exception stealing lease for partition " + stealee.getPartitionId(), e);
	            			this.host.getEventProcessorOptions().notifyOfException(this.host.getHostName(), e, EventProcessorHostActionStrings.STEALING_LEASE,
	            					stealee.getPartitionId());
	            		}
	            	}
	            }
            }

            // Update pump with new state of leases.
            for (String partitionId : allLeases.keySet())
            {
            	Lease updatedLease = allLeases.get(partitionId);
            	this.host.logWithHost(Level.FINER, "Lease on partition " + updatedLease.getPartitionId() + " owned by " + updatedLease.getOwner()); // DEBUG
            	if (updatedLease.getOwner().compareTo(this.host.getHostName()) == 0)
            	{
            		this.pump.addPump(partitionId, updatedLease);
            	}
            	else
            	{
            		Future<?> removing = this.pump.removePump(partitionId, CloseReason.LeaseLost);
            		if (removing != null)
            		{
            			removing.get();
            		}
            	}
            }
            
            onPartitionCheckCompleteTestHook();
    		
            try
            {
                Thread.sleep(leaseManager.getLeaseRenewIntervalInMilliseconds());
            }
            catch (InterruptedException e)
            {
            	// Bail on the thread if we are interrupted.
                this.host.logWithHost(Level.WARNING, "Sleep was interrupted", e);
                this.keepGoing = false;
				Thread.currentThread().interrupt();
				throw new RuntimeException(e);
            }
    	}
    }
    
    private Iterable<Lease> whichLeasesToSteal(ArrayList<Lease> stealableLeases, int haveLeaseCount)
    {
    	HashMap<String, Integer> countsByOwner = countLeasesByOwner(stealableLeases);
    	String biggestOwner = findBiggestOwner(countsByOwner);
    	int biggestCount = countsByOwner.get(biggestOwner);
    	ArrayList<Lease> stealTheseLeases = null;
    	
    	// If the number of leases is a multiple of the number of hosts, then the desired configuration is
    	// that all hosts own the name number of leases, and the difference between the "biggest" owner and
    	// any other is 0.
    	//
    	// If the number of leases is not a multiple of the number of hosts, then the most even configuration
    	// possible is for some hosts to have (leases/hosts) leases and others to have ((leases/hosts) + 1).
    	// For example, for 16 partitions distributed over five hosts, the distribution would be 4, 3, 3, 3, 3,
    	// or any of the possible reorderings.
    	//
    	// In either case, if the difference between this host and the biggest owner is 2 or more, then the
    	// system is not in the most evenly-distributed configuration, so steal one lease from the biggest.
    	// If there is a tie for biggest, findBiggestOwner() picks whichever appears first in the list because
    	// it doesn't really matter which "biggest" is trimmed down.
    	//
    	// Stealing one at a time prevents flapping because it reduces the difference between the biggest and
    	// this host by two at a time. If the starting difference is two or greater, then the difference cannot
    	// end up below 0. This host may become tied for biggest, but it cannot become larger than the host that
    	// it is stealing from.
    	
    	if ((biggestCount - haveLeaseCount) >= 2)
    	{
    		stealTheseLeases = new ArrayList<Lease>();
    		for (Lease l : stealableLeases)
    		{
    			if (l.getOwner().compareTo(biggestOwner) == 0)
    			{
    				stealTheseLeases.add(l);
    				this.host.logWithHost(Level.FINER, "Proposed to steal lease for partition " + l.getPartitionId() + " from " + biggestOwner);
  					break;
    			}
    		}
    	}
    	return stealTheseLeases;
    }
    
    private String findBiggestOwner(HashMap<String, Integer> countsByOwner)
    {
    	int biggestCount = 0;
    	String biggestOwner = null;
    	for (String owner : countsByOwner.keySet())
    	{
    		if (countsByOwner.get(owner) > biggestCount)
    		{
    			biggestCount = countsByOwner.get(owner);
    			biggestOwner = owner;
    		}
    	}
    	return biggestOwner;
    }
    
    private HashMap<String, Integer> countLeasesByOwner(Iterable<Lease> leases)
    {
    	HashMap<String, Integer> counts = new HashMap<String, Integer>();
    	for (Lease l : leases)
    	{
    		if (counts.containsKey(l.getOwner()))
    		{
    			Integer oldCount = counts.get(l.getOwner());
    			counts.put(l.getOwner(), oldCount + 1);
    		}
    		else
    		{
    			counts.put(l.getOwner(), 1);
    		}
    	}
    	for (String owner : counts.keySet())
    	{
    		this.host.log(Level.FINER, "host " + owner + " owns " + counts.get(owner) + " leases");
    	}
    	this.host.log(Level.FINER, "total hosts in sorted list: " + counts.size());
    	
    	return counts;
    }
}
