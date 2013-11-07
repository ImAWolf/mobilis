//
//  MXiConnection.m
//  MXi
//
//  Created by Richard Wotzlaw on 18.04.13.
//  Copyright (c) 2013 TU Dresden. All rights reserved.
//

#import "MXiConnection.h"

#import "MXiMultiUserChatMessage.h"
#import "XMPPRoomMemoryStorage.h"

@interface MXiConnection () <XMPPRoomDelegate>

@property (strong, nonatomic) NSMutableArray *connectedMUCRooms;
@property dispatch_queue_t room_queue;

@end

@implementation MXiConnection

+ (id)connectionWithJabberID:(NSString *)aJabberID
                    password:(NSString *)aPassword
                    hostName:(NSString *)aHostName
                        port:(NSInteger)port
              coordinatorJID:(NSString *)theCoordinatorJID
            serviceNamespace:(NSString *)theServiceNamespace
                 serviceType:(ServiceType)serviceType
            presenceDelegate:(id <MXiPresenceDelegate>)aPresenceDelegate
              stanzaDelegate:(id <MXiStanzaDelegate>)aStanzaDelegate
                beanDelegate:(id <MXiBeanDelegate>)aBeanDelegate
   listeningForIncomingBeans:(NSArray *)theIncomingBeanPrototypes
{
	return [[self alloc] initWithJabberID:aJabberID
                                 password:aPassword
                                 hostName:aHostName
                                     port:port
                           coordinatorJID:theCoordinatorJID
                         serviceNamespace:theServiceNamespace
                              serviceType:serviceType
                         presenceDelegate:aPresenceDelegate
                           stanzaDelegate:aStanzaDelegate
                             beanDelegate:aBeanDelegate
                listeningForIncomingBeans:theIncomingBeanPrototypes];
}

- (id)initWithJabberID:(NSString *)aJabberID
                    password:(NSString *)aPassword
                    hostName:(NSString *)aHostName
                        port:(NSInteger)port
              coordinatorJID:(NSString *)theCoordinatorJID
            serviceNamespace:(NSString *)theServiceNamespace
                 serviceType:(ServiceType)serviceType
            presenceDelegate:(id <MXiPresenceDelegate>)aPresenceDelegate
              stanzaDelegate:(id <MXiStanzaDelegate>)aStanzaDelegate
                beanDelegate:(id <MXiBeanDelegate>)aBeanDelegate
   listeningForIncomingBeans:(NSArray *)theIncomingBeanPrototypes
{
    self = [super init];
    if (self) {
        XMPPJID* tempJid = [XMPPJID jidWithString:aJabberID];
        [self setJabberID:tempJid];
        [self setPassword:aPassword];
        [self setServiceType: serviceType];
        if (aHostName && ![aHostName isEqualToString:@""]) {
            [self setHostName:aHostName];
        } else {
            [self setHostName:[tempJid domain]];
        }
        [self setPort:port];
        [self setCoordinatorJID:theCoordinatorJID];
        [self setServiceNamespace:theServiceNamespace];
        [self setPresenceDelegate:aPresenceDelegate];
        [self setStanzaDelegate:aStanzaDelegate];
        [self setBeanDelegate:aBeanDelegate];
        [self setIncomingBeanPrototypes:theIncomingBeanPrototypes];

        [self setupStream];
        [self connect];
    }
    return self;
}

- (BOOL)reconnectWithJabberID:(NSString *)aJabberID
					 password:(NSString *)aPassword
					 hostname:(NSString *)aHostname
						 port:(NSInteger )thePort
				   coordinatorJID:(NSString *)theCoordinatorJID
			 serviceNamespace:(NSString *)theServiceNamespace {
	[self disconnect];
	
	[self setJabberID:[XMPPJID jidWithString:aJabberID]];
	[self setPassword:aPassword];
	if (aHostname && ![aHostname isEqualToString:@""]) {
		[self setHostName:aHostname];
	}
	[self setPort:thePort];
	[self setCoordinatorJID:theCoordinatorJID];
	[self setServiceNamespace:theServiceNamespace];
	
	return [self connect];
}

#pragma mark - XEP-0045: Multi-User-Chat

- (void)connectToMultiUserChatRoom:(NSString *)roomJID
{
    if (!_connectedMUCRooms) {
        self.connectedMUCRooms = [NSMutableArray arrayWithCapacity:5];
        _room_queue = dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0);
    }
    XMPPRoom *room = [[XMPPRoom alloc] initWithRoomStorage:[[XMPPRoomMemoryStorage alloc] init]
                                                       jid:[XMPPJID jidWithString:roomJID]
                                             dispatchQueue:_room_queue];
    [room activate:self.xmppStream];
    [room addDelegate:self delegateQueue:_room_queue];
    [room joinRoomUsingNickname:@"acdsense_bot_DG" history:nil];
}

- (void)leaveMultiUserChatRoom:(NSString *)roomJID
{
    XMPPRoom *roomToLeave = nil;
    for (XMPPRoom *room in _connectedMUCRooms) {
        if ([[room.roomJID full] isEqualToString:roomJID]) {
            [room leaveRoom];
            roomToLeave = room;
            break;
        }
    }
    [_connectedMUCRooms removeObject:roomToLeave];
}

- (void)sendMessage:(NSString *)message toRoom:(NSString *)roomJID;
{
    XMPPJID *outgoingJID = [XMPPJID jidWithString:roomJID];
    for (XMPPRoom *room in _connectedMUCRooms) {
        if ([[room.roomJID full] isEqualToString:[outgoingJID bare]]) {
            [room sendMessage:[MXiMultiUserChatMessage messageWithBody:message]];
        }
    }
}

#pragma mark - XMPPStreamDelegate

- (void)xmppStreamDidConnect:(XMPPStream* )sender {
	NSError* error = nil;
	[self.xmppStream authenticateWithPassword:self.password error:&error];
}

- (void)xmppStreamDidDisconnect:(XMPPStream *)sender withError:(NSError *)error {
	[self.presenceDelegate didDisconnectWithError:error];
}

- (void)xmppStreamDidAuthenticate:(XMPPStream* )sender {
	[self goOnline];
    if (self.serviceType == MULTI)
        [self discoverServices];
    else [self discoverServiceInstances];
}

- (void)xmppStream:(XMPPStream *)sender didNotAuthenticate:(NSXMLElement *)error {
	NSLog(@"libMXi.a: Authentication failed");
	
	[self.presenceDelegate didFailToAuthenticate:error];
}

- (BOOL)xmppStream:(XMPPStream *)sender didReceiveIQ:(XMPPIQ *)iq {
	BOOL success = [self.stanzaDelegate didReceiveIQ:iq];

	NSLog(@"Received iq: %@", [iq prettyXMLString]);
	NSXMLElement* childElement = [iq childElement];
	
	// Did we get a service discovery response?
    [self validateDiscoveredServices:childElement];

    // Did we get an incoming mobilis bean?
	for (MXiBean<MXiIncomingBean>* prototype in self.incomingBeanPrototypes) {
		if ([[[prototype class] elementName] isEqualToString:[childElement name]] &&
				[[[prototype class] iqNamespace] isEqualToString:[childElement xmlns]] &&
				[[MXiIQTypeLookup stringValueForIQType:[prototype beanType]]
					isEqualToString:[iq attributeStringValueForName:@"type"]]) {
			// parse the iq data into the bean object
			[MXiBeanConverter beanFromIQ:iq intoBean:prototype];
			// inform the app about this incoming bean
			[self.beanDelegate didReceiveBean:prototype];
		}
	}

    // Did we get an incoming service creation response
    [self handleServiceResponse:iq];
	
	return success;
}

- (void)validateDiscoveredServices:(NSXMLElement *)serviceDiscoveryElement
{
    if ([[serviceDiscoveryElement name] isEqualToString:@"serviceDiscovery"]) {
		NSArray* discoveredServiceElements = [serviceDiscoveryElement children];
		if (self.serviceType == SINGLE)
            [self singleModeServiceDetection:discoveredServiceElements];
        else [self multiModeServiceDetection:discoveredServiceElements];
	}
}
- (void)singleModeServiceDetection:(NSArray *)discoveredServiceElements
{
    for (int i = 0; i < [discoveredServiceElements count]; i++) {
        NSXMLElement* discoveredServiceElement = [discoveredServiceElements objectAtIndex:i];
        if (i == 0) {
            // choose the first discovered service jid by default
            [self setServiceJID:[discoveredServiceElement attributeStringValueForName:@"jid"]];
            [self setServiceName:[discoveredServiceElement attributeStringValueForName:@"serviceName"]];
        }
        [self.presenceDelegate didDiscoverServiceWithNamespace:[discoveredServiceElement attributeStringValueForName:@"namespace"]
                                                     name:[discoveredServiceElement attributeStringValueForName:@"serviceName"]
                                                  version:[discoveredServiceElement attributeIntegerValueForName:@"version"]
                                               atJabberID:[discoveredServiceElement attributeStringValueForName:@"jid"]];
    }
}
- (void)multiModeServiceDetection:(NSArray *)discoveredServiceElements
{
    BOOL concreteServiceFound = NO;
    for (NSXMLElement *discoveredElement in discoveredServiceElements)
        if ([[discoveredElement attributeStringValueForName:@"namespace"] isEqualToString:self.serviceNamespace])
            if ([discoveredElement attributeStringValueForName:@"jid"] && [discoveredElement attributeStringValueForName:@"serviceName"]) {
                [self.presenceDelegate didDiscoverServiceWithNamespace:[discoveredElement attributeStringValueForName:@"namespace"]
                                                                  name:[discoveredElement attributeStringValueForName:@"serviceName"]
                                                               version:[discoveredElement attributeIntegerValueForName:@"version"]
                                                            atJabberID:[discoveredElement attributeStringValueForName:@"jid"]];
                concreteServiceFound = YES;
            }
    if (!concreteServiceFound && discoveredServiceElements.count > 0)
        [self discoverServiceInstances];
}

- (void)handleServiceResponse:(XMPPIQ *)iq
{
    NSArray *iqChildren = [iq children];
    if (iqChildren.count != 1)
        return;

    if ([iq elementForName:@"createNewServiceInstance"]) {
        [self.presenceDelegate serviceInstanceCreating];
    }
    if ([iq elementForName:@"sendNewServiceInstance"]) {
        NSXMLElement *element = [iq elementForName:@"sendNewServiceInstance"];
        NSString *serviceJID = [[element elementForName:@"jidOfNewService"] stringValue];
        NSString *serviceVersion = [[element elementForName:@"serviceVersion"] stringValue];

        [self.presenceDelegate didCreateServiceWithJabberID:serviceJID andVersion:serviceVersion];
        
        [self sendServiceCreationAcknowledgement];
    }
}
- (void)sendServiceCreationAcknowledgement
{
    NSXMLElement *ackIQ = [NSXMLElement elementWithName:@"iq"];
    [ackIQ addAttributeWithName:@"to" stringValue:self.coordinatorJID];
    [ackIQ addAttributeWithName:@"from" stringValue:self.jabberID.full];
    [ackIQ addAttributeWithName:@"type" stringValue:@"result"];
    [ackIQ addChild:[[NSXMLElement alloc] initWithName:@"sendNewServiceInstance" xmlns:CoordinatorService]];

    [self.xmppStream sendElement:ackIQ];
}

- (void)xmppStream:(XMPPStream *)sender didReceiveMessage:(XMPPMessage *)message {
	[self.stanzaDelegate didReceiveMessage:message];
}

- (void)xmppStream:(XMPPStream *)sender didReceivePresence:(XMPPPresence *)presence {
	[self.stanzaDelegate didReceivePresence:presence];
}

- (void)xmppStream:(XMPPStream *)sender didReceiveError:(NSXMLElement *)error {
	[self.stanzaDelegate didReceiveError:error];
}

/*
 * Preparing and closing the xmpp stream
 */

- (void)setupStream {
    _xmppStream = [[XMPPStream alloc] init];
	// inform this very object about stream events
	[self.xmppStream addDelegate:self delegateQueue:dispatch_get_main_queue()];
}

- (void)goOnline {
	XMPPPresence* presence = [XMPPPresence presence];
	[self.xmppStream sendElement:presence];
	
	[self.presenceDelegate didAuthenticate];
}

- (void)goOffline {
	XMPPPresence* presence = [XMPPPresence presenceWithType:@"unavailable"];
	[self.xmppStream sendElement:presence];
}

- (void)discoverServices
{
    NSXMLElement* discoElement =
    [NSXMLElement elementWithName:@"serviceDiscovery"
                            xmlns:CoordinatorService];
    NSXMLElement* iqElement = [NSXMLElement elementWithName:@"iq"];
    [iqElement addAttributeWithName:@"to"
                        stringValue:[self coordinatorJID]];
    [iqElement addAttributeWithName:@"type" stringValue:@"get"];
    [iqElement addChild:discoElement];

    [self sendElement:iqElement];
}

- (void)discoverServiceInstances
{
	NSXMLElement* namespaceElement =
		[NSXMLElement elementWithName:@"serviceNamespace"];
	[namespaceElement setStringValue:[self serviceNamespace]];
	NSXMLElement* discoElement =
    [NSXMLElement elementWithName:@"serviceDiscovery"
                            xmlns:CoordinatorService];
	[discoElement addChild:namespaceElement];
	NSXMLElement* iqElement = [NSXMLElement elementWithName:@"iq"];
	[iqElement addAttributeWithName:@"to"
						stringValue:[self coordinatorJID]];
	[iqElement addAttributeWithName:@"type" stringValue:@"get"];
	[iqElement addChild:discoElement];
	
	[self sendElement:iqElement];
}

- (BOOL)connect {
	if ([self.xmppStream isConnected]) {
		return YES;
	}
	
	[self.xmppStream setMyJID:[self jabberID]];
	[self.xmppStream setHostName:[self hostName]];
	[self.xmppStream setHostPort:[self port]];
	
	XMPPReconnect* reconnect = [[XMPPReconnect alloc] init];
	[reconnect activate:self.xmppStream];
	
	/*
	NSLog(@"Trying to connect with:");
	NSLog(@" - myJid: %@", [xmppStream myJID]);
	NSLog(@" - myPassword: %@", password);
	NSLog(@" - hostname: %@", [xmppStream hostName]);
	NSLog(@" - port: %d", [xmppStream hostPort]);
	*/
	
	NSError* error = nil;
	if (![self.xmppStream connectWithTimeout:30.0 error:&error]) {
		NSLog(@"Couldn't connect because of error: %@", [error localizedDescription]);
		return NO;
	}
	
	return YES;
}

- (void)sendTestMessageWithContent:(NSString *)content
								to:(NSString *)to {
	NSXMLElement *body = [NSXMLElement elementWithName:@"body"];
	[body setStringValue:content];
	
	NSXMLElement *message = [NSXMLElement elementWithName:@"message"];
	[message addAttributeWithName:@"type" stringValue:@"chat"];
	[message addAttributeWithName:@"to" stringValue:to];
	[message addAttributeWithName:@"from" stringValue:[self.jabberID full]];
	[message addChild:body];
	
	[self.xmppStream sendElement:message];
}

- (void)sendElement:(NSXMLElement* )element {
	NSLog(@"Sent: %@", [element prettyXMLString]);
	
	[self.xmppStream sendElement:element];
}

- (void)sendBean:(MXiBean<MXiOutgoingBean>* )bean {
	[bean setFrom:self.jabberID];
    if (self.serviceType == SINGLE) {
	    [bean setTo:[XMPPJID jidWithString:self.serviceJID]];
    }

	[self sendElement:[MXiBeanConverter beanToIQ:bean]];
}

- (void)createServiceInstanceWithServiceName:(NSString *)serviceName
                             servicePassword:(NSString *)password
                            serviceNamespace:(NSString *)serviceNamespace
{
    @autoreleasepool {
        NSXMLElement *serviceIQ = [NSXMLElement elementWithName:@"iq"];
        NSXMLElement *serviceBean = [NSXMLElement elementWithName:@"createNewServiceInstance"
                                                            xmlns:CoordinatorService];

        [serviceBean addChild:[[NSXMLElement alloc] initWithName:@"serviceNamespace" stringValue:serviceNamespace]];
        [serviceBean addChild:[[NSXMLElement alloc] initWithName:@"password" stringValue:password]];
        [serviceBean addChild:[[NSXMLElement alloc] initWithName:@"serviceName" stringValue:serviceName]];

        [serviceIQ addAttributeWithName:@"to" stringValue:self.coordinatorJID];
        [serviceIQ addAttributeWithName:@"from" stringValue:self.jabberID.full];
        [serviceIQ addAttributeWithName:@"type" stringValue:@"set"];

        [serviceIQ addChild:serviceBean];

        [self.xmppStream sendElement:serviceIQ];
    }
}

- (void)disconnect {
	[self goOffline];
	[self.xmppStream disconnect];
}

#pragma mark - XMPPRoomDelegate

- (void)xmppRoomDidJoin:(XMPPRoom *)sender
{
    [self.connectedMUCRooms addObject:sender];
    if (_mucDelegate && [_mucDelegate respondsToSelector:@selector(connectionToRoomEstablished:)]) {
        [_mucDelegate performSelector:@selector(connectionToRoomEstablished:) withObject:[sender.roomJID bare]];
    }
}

- (void)xmppRoom:(XMPPRoom *)sender didReceiveMessage:(XMPPMessage *)message fromOccupant:(XMPPJID *)occupantJID
{
    if (_mucDelegate && [_mucDelegate respondsToSelector:@selector(didReceiveMultiUserChatMessage:fromUser:publishedInRoom:)]) {
        [_mucDelegate didReceiveMultiUserChatMessage:[[message elementForName:@"body"] stringValue]
                                            fromUser:occupantJID.full
                                     publishedInRoom:sender.roomJID.full];
    }
}

- (void)xmppRoom:(XMPPRoom *)sender occupantDidJoin:(XMPPJID *)occupantJID withPresence:(XMPPPresence *)presence
{
    if (_mucDelegate && [_mucDelegate respondsToSelector:@selector(userWithJid:didJoin:room:)]) {
        [_mucDelegate userWithJid:occupantJID.full didJoin:presence.status room:[sender.roomJID full]];
    }
}

- (void)xmppRoom:(XMPPRoom *)sender occupantDidLeave:(XMPPJID *)occupantJID withPresence:(XMPPPresence *)presence
{
    if (_mucDelegate && [_mucDelegate respondsToSelector:@selector(userWithJid:didLeaveRoom:)]) {
        [_mucDelegate userWithJid:occupantJID.full didLeaveRoom:[sender.roomJID full]];
    }
}

- (void)xmppRoom:(XMPPRoom *)sender occupantDidUpdate:(XMPPJID *)occupantJID withPresence:(XMPPPresence *)presence
{
    if (_mucDelegate && [_mucDelegate respondsToSelector:@selector(userWithJid:didUpdate:inRoom:)]) {
        [_mucDelegate userWithJid:occupantJID.full didUpdate:presence.status inRoom:[sender.roomJID full]];
    }
}

@end
