/**
 * Open Wonderland
 *
 * Copyright (c) 2010 - 2011, Open Wonderland Foundation, All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 *
 * The Open Wonderland Foundation designates this particular file as
 * subject to the "Classpath" exception as provided by the Open Wonderland
 * Foundation in the License file that accompanied this code.
 */

/*
 * Copyright 2007 Sun Microsystems, Inc.
 *
 * This file is part of jVoiceBridge.
 *
 * jVoiceBridge is free software: you can redistribute it and/or modify 
 * it under the terms of the GNU General Public License version 2 as 
 * published by the Free Software Foundation and distributed hereunder 
 * to you.
 *
 * jVoiceBridge is distributed in the hope that it will be useful, 
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Sun designates this particular file as subject to the "Classpath"
 * exception as provided by Sun in the License file that accompanied this 
 * code. 
 */

package com.sun.voip.server;

import com.sun.voip.CallParticipant;
import com.sun.voip.Logger;
import java.text.ParseException;
import java.util.logging.Level;

import javax.sip.*; 
import javax.sip.address.*; 
import javax.sip.header.*; 
import javax.sip.message.*; 

import java.util.ArrayList;
import java.util.Enumeration;
import java.util.EventObject;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.Properties;
import java.util.TooManyListenersException;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;

/**
 * The SIP Server sets up a SIP Stack and handles SIP requests and responses.  
 *
 * It is the first point of contact for all incoming SIP messages.  
 *
 * It listens for SIP messages on a default port of 5060 
 * (override by the NIST-SIP property gov.nist.jainsip.stack.enableUDP), 
 * and forwards the request to the appropriate SipListener according to 
 * the SIP CallId of the SIP message.
 *
 * Properties:
 * com.sun.voip.server.SIPProxy = NIST_PROXY_SERVER
 * gov.nist.jainsip.stack.enableUDP = 5060;
 */
public class SipServer implements SipListener {
    /*
     * default port for SIP communication
     */
    public static final int SIP_PORT = 5060; 

    /*
     * ip addresses of the SIP Proxy (NIST Proxy Server by default)
     */
    private static String defaultSipProxy;

    /*
     * flag to indicate whether to send SIP Uri's to a proxy or directly
     * to the target endpoint.
     */
    private static boolean sendSipUriToProxy = false;

    /*
     * hashtable of SipListeners
     */
    private static Hashtable sipListenersTable;

    /*
     * sip stack variables
     */
    private static SipFactory sipFactory; 
    private static AddressFactory addressFactory; 
    private static HeaderFactory headerFactory; 
    private static MessageFactory messageFactory; 
    private static SipStack sipStack; 
    private static SipProvider sipProvider; 
    private static Iterator listeningPoints; 
    private static SipServerCallback sipServerCallback;
    private static InetSocketAddress sipAddress;

    /**
     * Constructor
     */
    public SipServer(String localHostAddress, Properties properties) { 
        sipListenersTable = new Hashtable();
        sipServerCallback = new SipServerCallback();
        setup(localHostAddress, properties); 
    } 

    /**
     * Sets up and initializes the sip server, including the sipstack.
     */
    private void setup(String localHostAddress, Properties properties) { 
        properties.setProperty("javax.sip.IP_ADDRESS", localHostAddress);

	GatewayManager.getGatewayInfo();

        /*
	 * Obtain an instance of the singleton SipFactory 
	 */
        sipFactory = SipFactory.getInstance(); 

        /* 
         * Set path name of SipFactory to implementation. 
         * used to setup the classpath
         */
        sipFactory.setPathName("gov.nist"); 

        try { 
            /*
	     * Create SipStack object 
	     */
            sipStack = (SipStack)sipFactory.createSipStack(properties); 
            /*
	     * Create AddressFactory 
	     */
            addressFactory = sipFactory.createAddressFactory(); 
            /*
             * Create HeaderFactory 
	     */
            headerFactory = sipFactory.createHeaderFactory(); 
            /*
	     * Create MessageFactory 
	     */
            messageFactory = sipFactory.createMessageFactory(); 
        } catch(PeerUnavailableException e) { 
            /* 
	     * could not find gov.nist.ri.jain.protocol.ip.sip.
             * SipStackImpl in the classpath 
             */
            Logger.exception("could not stsart sip stack.", e);
            System.exit(-1);
        } catch(SipException e) { 
            /*
	     * could not create SipStack for some other reason 
	     */
            Logger.exception("could not start sip stack.", e); 
            System.exit(-1);
        } 

	ListeningPoint lp = null;

        try { 
            /*
             * Create SipProvider based on the first ListeningPoint 
             * Note that this call will block until somebody sends us
             * a message because we dont know what IP address and
             * port to assign to outgoing messages from this provider
             * at this point. 
             */
            String s = System.getProperty(
		"gov.nist.jainsip.stack.enableUDP", String.valueOf(SIP_PORT));

	    int sipPort = Integer.parseInt(s);

            lp = sipStack.createListeningPoint(sipPort, "tcp");

            sipProvider = sipStack.createSipProvider(lp); 

            sipProvider.addSipListener(this); 

            lp = sipStack.createListeningPoint(sipPort, "udp");

	    sipAddress = new InetSocketAddress(sipStack.getIPAddress(), sipPort);

            sipProvider = sipStack.createSipProvider(lp); 

            sipProvider.addSipListener(this); 

	    Logger.println("");
            Logger.println("Bridge private address:   " 
	        + properties.getProperty("javax.sip.IP_ADDRESS"));
            Logger.println("Bridge private SIP port:  " + lp.getPort());

            /*
	     * get IPs of the SIP Proxy server
	     */
            defaultSipProxy = System.getProperty("com.sun.voip.server.SIPProxy", "");

            /* 
	     * Initialize SipUtil class.  Do this last so that
             * the other sip stack variables are initialized
             */
            SipUtil.initialize();

	    GatewayManager.registerGateways();
        } catch(NullPointerException e) { 
            Logger.exception("Stack has no ListeningPoints", e); 
            System.exit(-1);
        } catch(ObjectInUseException e) { 
            Logger.exception("Stack has no ListeningPoints", e); 
            System.exit(-1);
        } catch(TransportNotSupportedException e) {
	    Logger.exception("TransportNotSupportedException", e);
            System.exit(-1);
	} catch(TooManyListenersException e) {
	    Logger.exception("TooManyListenersException", e);
            System.exit(-1);
	} catch(InvalidArgumentException e) {
	    Logger.exception("InvalidArgumentException", e);
            System.exit(-1);
	}

        Logger.println("Default SIP Proxy:        " + defaultSipProxy);
	Logger.println("");
    }

    /**
     * set flag to indicate whether to send SIP Uri's to a proxy or directly
     * to the target endpoint.
     */
    public static void setSendSipUriToProxy(boolean sendSipUriToProxy) {
	SipServer.sendSipUriToProxy = sendSipUriToProxy;
    }

    public static boolean getSendSipUriToProxy() {
	return sendSipUriToProxy;
    }

    /**
     * Get the IP Address of the SIP Proxy.
     * @return SipProxy String with dotted IP address
     */
    public static String getDefaultSipProxy() {
	return defaultSipProxy;
    }

    /**
     * Set the IP address of the SIP Proxy.
     * @param ip String with dotted IP address
     */
    public static void setDefaultSipProxy(String defaultSipProxy) {
	SipServer.defaultSipProxy = defaultSipProxy;
    }

    public static InetSocketAddress getSipAddress() {
	return sipAddress;
    }

    /**
     * Process transaction timeout.  Forwards the event to 
     * the appropriate sipListener
     * @param transactionTimeOutEvent The timeout event
     */
    public void processTimeout(javax.sip.TimeoutEvent timeoutEvent) { 
        try {
            /*
             * FIXME: Possible BUG - getMessage() for a transaction
             * timed out event returns null, so we're unable to 
             * determine the SIP callId of the event.  
             *
             * Workaround: enumerate through the agents table and 
             * call processTimeOut() of all agents that are in 
             * the ONE_PARTY_INVITED state.
             */
            //Enumeration e = sipListenersTable.elements();
            //while (e.hasMoreElements()) {
                //CallSetupAgent callSetupAgent = (CallSetupAgent)e.nextElement();
                //int state = callSetupAgent.getState();
                //if (state == CallSetupAgent.CALL_PARTICIPANT_INVITED) {
                //    // callSetupAgent.processTimeOut(timeoutEvent);
		//    Logger.error("timeout:  " + callSetupAgent);
		//}
            //}
        } catch (Exception e) {
            /* 
	     * FIXME: if any exception happens at this stage, 
             * we should send back a 500 Internal Server Error
             */
	    Logger.exception("processTimeout", e);
        }
    }

    /**
     * Process requests received.  Forwards the request to 
     * the appropriate SipListener. 
     * @param requestEvent the request event
     */
    public void processRequest(RequestEvent requestEvent) { 
        try {
            Request request = requestEvent.getRequest();

	   //System.out.println("GOT REQUEST:  " + request);

	    CallIdHeader callIdHeader = (CallIdHeader)
		request.getHeader(CallIdHeader.NAME);

	    String sipCallId = callIdHeader.getCallId();

            SipListener sipListener = findSipListener(requestEvent);

	    /*
	     * If there's an existing listener pass the request there.
	     */
            if (sipListener != null) {
	        if (request.getMethod().equals(Request.INVITE)) {
		    duplicateInvite(request);
		    return;
		}

                sipListener.processRequest(requestEvent);
		return;
            } else {
		if (request.getMethod().equals(Request.REGISTER)) {
		    handleRegister(request, requestEvent);
		} else if (!request.getMethod().equals(Request.INVITE)) {
                    Logger.writeFile("sipListener could not be found for "
                        + sipCallId + " " + request.getMethod()
		        + ".  Ignoring");
		    return;
                }
	    }

	    /*
	     * An INVITE for an incoming call goes to the IncomingCallHandler.
	     */
	    if (request.getMethod().equals(Request.INVITE)) {
		if (SipIncomingCallAgent.addSipCallId(sipCallId) == false) {
		    duplicateInvite(request);
		    return;
		}

		CallParticipant cp = new CallParticipant();

		String s = SipUtil.getCallIdFromSdp(request);

		if (s != null) {
		    if (Logger.logLevel >= Logger.LOG_MOREINFO) {
			Logger.println("Using callId from SDP in INVITE: "
			    + s);
		    }
		    cp.setCallId(s);
		}

	  	s = SipUtil.getConferenceIdFromSdp(request);

		if (s != null) {
		    String[] tokens = s.split(":");

		    String conferenceId = tokens[0].trim();

		    if (conferenceId.length() == 0) {
			conferenceId = null;
		    }

		    cp.setConferenceId(conferenceId);

		    if (tokens.length > 1) {
                        cp.setMediaPreference(tokens[1]);
                    }

		    if (tokens.length > 2) {
			cp.setConferenceDisplayName(tokens[2]);
		    }
		}

		if (SipUtil.getUserNameFromSdp(request) != null) {
		    cp.setName(SipUtil.getUserNameFromSdp(request));
		} else {
		    cp.setName(SipUtil.getFromName(requestEvent));
		}

		cp.setDistributedBridge(
		    SipUtil.getDistributedBridgeFromSdp(request));
		
		if (SipUtil.getToPhoneNumber(requestEvent).equals("6666")) {
		    cp.setPhoneNumber("sip:6666@" 
			+ SipUtil.getFromHost(requestEvent));
		} else {
		    cp.setPhoneNumber(
		        SipUtil.getFromPhoneNumber(requestEvent));
		}

		new IncomingCallHandler(cp, requestEvent);
		return;
	    }
        } catch (Exception e) {
            /*
	     * FIXME: if any exception happens at this stage, 
             * we should send back a 500 Internal Server Error
             */
	    Logger.exception("processRequest", e);
        }
    }

    private void duplicateInvite(Request request)
    {
      try
      {
	FromHeader fromHeader = (FromHeader) request.getHeader(FromHeader.NAME);
        ToHeader toHeader = (ToHeader) request.getHeader(ToHeader.NAME);
        
	String from = fromHeader.getAddress().toString();
        String to = toHeader.getAddress().toString();

        Response response = messageFactory.createResponse(Response.OK, request);
        Logger.writeFile("SipServer:  duplicate INVITE from " + from + " to " + to);
        Logger.println("RESPONSE " + response);

        sipProvider.sendResponse(response);
    }
      catch (SipException ex)
      {
        java.util.logging.Logger.getLogger(SipServer.class.getName()).log(Level.SEVERE, null, ex);
      }
      catch (ParseException ex)
      {
        java.util.logging.Logger.getLogger(SipServer.class.getName()).log(Level.SEVERE, null, ex);
      }
    }

    private void handleRegister(Request request, RequestEvent requestEvent) 
	    throws Exception {

	if (Logger.logLevel >= Logger.LOG_INFO) {
	    Logger.println(request.toString());
	}

	Response response = messageFactory.createResponse(
	    Response.OK, request);

        ServerTransaction serverTransaction = requestEvent.getServerTransaction();

	if (Logger.logLevel >= Logger.LOG_INFO) {
	    Logger.println("Response " + response);
	}

        if (serverTransaction != null) {
            serverTransaction.sendResponse(response);
        } else {
	    sipProvider.sendResponse(response);
	}
    }

    /**
     * Process responses received.  Forward the responses to 
     * the appropriate SipListener.
     * @param responseReceivedEvent the response event
     */
    public void processResponse(ResponseEvent responseReceivedEvent) { 
	if (responseReceivedEvent.getClientTransaction() == null) {
            Logger.error("processResponse:  clientTransaction is null! " 
		+ responseReceivedEvent.getResponse());
	    return;
        }

        try {
            SipListener sipListener = findSipListener(responseReceivedEvent);
            if (sipListener != null) {
                sipListener.processResponse(responseReceivedEvent);
            } else {
                /* 
		 * a BYE message could come from a party that already
                 * has its entry removed from the SipListenersTable.  Ignoring.
                 * This is the desired behaviour if we wished to send BYE
                 * requests to a party that just got hung up on (e.g.
                 * if party A hangs up, we send a BYE to party B).
                 */
                Response response = responseReceivedEvent.getResponse();
                if (response.getStatusCode() != Response.OK) {
		    CallIdHeader callIdHeader = (CallIdHeader)
                        response.getHeader(CallIdHeader.NAME);

                    Logger.writeFile("sipListener could not be found for "
                        + callIdHeader.getCallId() + " " 
                        + response.getStatusCode() + ".  Ignoring");
		}
            }
        } catch (Exception e) {
            /* FIXME: if any exception happens at this stage, 
             * we should send back a 500 Internal Server Error
             */
	    Logger.exception("processResponse", e);
        }
    }

    /**
     * Finds the SipListener responsible for handling the SIP transaction 
     * associated with the SIP callId
     * @param event the EventObject
     * @throws Exception general exception when an error occurs
     * @return the SipListener for this sipEvent
     */
    private SipListener findSipListener(EventObject event) {
	String sipCallId = null;

        try {
	    CallIdHeader callIdHeader;

	    if (event instanceof RequestEvent) {
                Request request = ((RequestEvent)event).getRequest();

		callIdHeader = (CallIdHeader)
                    request.getHeader(CallIdHeader.NAME);
	    } else if (event instanceof ResponseEvent) {
		Response response = ((ResponseEvent)event).getResponse();

		callIdHeader = (CallIdHeader)
                    response.getHeader(CallIdHeader.NAME);
	    } else {
		Logger.error("Invalid event object " + event);
	       	return null;
	    } 

	    sipCallId = callIdHeader.getCallId();

            synchronized (sipListenersTable) {
                return (SipListener)sipListenersTable.get(sipCallId);
            }
        } catch (NullPointerException e) {
            /*
	     * most likely due to a null sipCallId
	     */
            if (sipCallId == null || "".equals(sipCallId)) {
                Logger.exception("could not get SIP CallId from incoming"
                        + " message.  Dropping message", e);
            }
            throw e;
        }
    }

    /**
     * returns the sipStack
     * @return the sipStack
     */
    public static SipStack getSipStack() {
	return sipStack;
    }

    /**
     * returns the headerFactory 
     * @return the headerFactory 
     */
    public static HeaderFactory getHeaderFactory() {
        return headerFactory;
    }

    /**
     * returns the addressFactory 
     * @return addressFactory the addressFactory 
     */
    public static AddressFactory getAddressFactory() {
        return addressFactory;
    }
    
    /**
     * returns the messageFactory 
     * @return the messageFactory 
     */
    public static MessageFactory getMessageFactory() {
        return messageFactory;
    }

    /**
     * returns the sipProvider 
     * @return the sipProvider
     */
    public static SipProvider getSipProvider() {
        return sipProvider;
    }

    /**
     * returns the sipServerCallback
     * @return the sipServerCallback
     */
    public static SipServerCallback getSipServerCallback() {
        return SipServer.sipServerCallback;
    }
    
    /**
     * Inner class responsible for handling SIP agent registrations and 
     * unregistrations.  Used for callback purposes
     */
    class SipServerCallback {
        /**
         * registers a SIP agent with the given key.
         * @param key the key used to distinguish the SIP agent
         * @param agent the SIP agent
         */
        public void addSipListener(String key, SipListener sipListener) {
            synchronized (sipListenersTable) {
                if (!sipListenersTable.containsKey(key)) {
                    sipListenersTable.put(key, sipListener);
                } else {
                    Logger.error("key:  " + key + " already mapped!");
		}
            }
        }
        
        /**
         * unregisters a SIP agent with the given key.
         * @param key the key used to distinguish the SIP agent
         */
        public void removeSipListener(String key) {
            synchronized (sipListenersTable) {
                if (sipListenersTable.containsKey(key)) {
                    sipListenersTable.remove(key);
                } else {
                    Logger.println("could not find a SipListener "
                        + "entry to remove with the key:" + key);
	        }
            }
        }
    }

    public static ServerTransaction getServerTransaction(
	    RequestEvent requestEvent) throws TransactionDoesNotExistException,
	    TransactionUnavailableException {

	Request request = requestEvent.getRequest();

	ServerTransaction st = null;

	try {
	    getSipProvider().getNewServerTransaction(request);
	} catch (TransactionAlreadyExistsException e) {
	    Logger.println("Server transaction already exists for " + request);

	    st = requestEvent.getServerTransaction();

	    if (st == null) {
		Logger.println("st still null!");

		//st = sipStack.findTransaction((SIPRequest) request, true);
	    }
	} 

	if (st == null) {
	    Logger.println("Server transaction not found for " + request);

	    throw new TransactionDoesNotExistException(
                        "Server transaction not found for " + request);
	}

	return st;
    }

    public void processDialogTerminated(DialogTerminatedEvent dte) {
        if (Logger.logLevel >= Logger.LOG_SIP) {
            Logger.println("processDialogTerminated called");
	}
    }

    public void  processTransactionTerminated(TransactionTerminatedEvent tte) {
        if (Logger.logLevel >= Logger.LOG_SIP) {
            Logger.println("processTransactionTerminated called");
	}
    }

    public void  processIOException(IOExceptionEvent ioee) {
        if (Logger.logLevel >= Logger.LOG_SIP) {
            Logger.println("processTransactionTerminated called");
	}
    }

}