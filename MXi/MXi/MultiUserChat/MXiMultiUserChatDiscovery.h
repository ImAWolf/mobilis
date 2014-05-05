//
// Created by Martin Weißbach on 10/21/13.
// Copyright (c) 2013 TU Dresden. All rights reserved.
//


#import <Foundation/Foundation.h>

extern NSString *const serviceDiscoInfoNS;
extern NSString *const serviceDiscoItemsNS;
extern NSString *const mucFeatureString;

@protocol MXiMultiUserChatDiscoveryDelegate;
@class MXiAbstractConnectionHandler;

/*!
    @class MXiMultiUserChatDiscovery
 
    This class implements the MultiUserChat discovery functionality.
    If a given XMPP domain supports multi user chat, a list of all publicly available rooms can be delivered by this implementation.
 */
@interface MXiMultiUserChatDiscovery : NSObject

/*!
    Create a new multi user chat service discovery for a given domain.
 
    @param  connectionHandler   A reference to the connection handler managing a respective server connection.
                                This server connection will be used for the multi-user-chat discovery.
    @param  domainName          The name of the domain – the URL – which is to be queried for multi user chat support.
    @param  delegate            The object implementing the MXiMultiUserChatDiscoveryDelegate protocol to be notified when
                                the service discovery is finished.
 */
+ (instancetype)multiUserChatDiscoveryWithConnectionHandler:(MXiAbstractConnectionHandler *)connectionHandler forDomainName:(NSString *)domainName andDelegate:(id <MXiMultiUserChatDiscoveryDelegate>)delegate;

/*!
    Launch the service discovery.
 
    @param  resultQueue     The queue on which delegate methods are to be invoked.
                            If nil, the results are dispatched to the main queue.
 */
- (void)startDiscoveryWithResultQueue:(dispatch_queue_t)resultQueue;

@end

/*!
    @protocol MXiMultiUserChatDiscovery
 
    Implement the methods of this protocol to get notified when Mulituserchatrooms were discovered.
 */
@protocol MXiMultiUserChatDiscoveryDelegate <NSObject>

/*!
    Method gets invoked when multi user chat rooms were discovered.
 
    @param  chatDiscovery   The MXiMultiUserChatDiscovery object that ran the XEP-30 disco.
    @param  chatRooms       An array of chat rooms offered by the domain that was under investigation.
    @param  domainName      The name of the domain which was under investigation.
 */
- (void)multiUserChatRoomsDiscovery:(MXiMultiUserChatDiscovery *)chatDiscovery discoveredChatRooms:(NSArray *)chatRooms inDomain:(NSString *)domainName;

@end