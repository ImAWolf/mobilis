//
// Created by Martin Weißbach on 10/21/13.
// Copyright (c) 2013 TU Dresden. All rights reserved.
//


#import <XMPPFramework/XMPPIQ.h>
#import <XMPPFramework/NSXMLElement+XMPP.h>
#import "MXiMultiUserChatDiscovery.h"
#import "MXiMultiUserChatRoom.h"
#import "MXiConnectionHandler.h"
#import "MXiConnection.h"
#import "MXiAbstractConnectionHandler.h"

NSString *const serviceDiscoInfoNS = @"http://jabber.org/protocol/disco#info";
NSString *const serviceDiscoItemsNS = @"http://jabber.org/protocol/disco#items";
NSString *const mucFeatureString = @"http://jabber.org/protocol/muc";

@interface MXiMultiUserChatDiscovery ()

@property (nonatomic) NSMutableArray *cachedDomainItems;
@property (nonatomic) NSMutableArray *discoveredRooms;

- (XMPPIQ *)constructInformationQueryWithAddressee:(NSString *)jid elementID:(NSString *)elementID queryType:(NSString *)queryType;

- (BOOL)isDiscoItemsResult:(XMPPIQ *)xmppiq;
- (BOOL)hasItemMUCFeature:(XMPPIQ *)xmppiq;
- (void)readRoomItemsFromIQ:(XMPPIQ *)xmppiq;

@end

@implementation MXiMultiUserChatDiscovery
{
    dispatch_queue_t _resultQueue;

    __strong NSString *_domainName;
    __weak id<MXiMultiUserChatDiscoveryDelegate> _delegate;
    __strong MXiAbstractConnectionHandler *__connectionHandler;
}

+ (instancetype)multiUserChatDiscoveryWithConnectionHandler:(MXiAbstractConnectionHandler *)connectionHandler
                                              forDomainName:(NSString *)domainName
                                                andDelegate:(id <MXiMultiUserChatDiscoveryDelegate>)delegate
{
    return [[self alloc] initWithInitWithConnectionHandler:connectionHandler forDomainName:domainName andDelegate:delegate];
}

- (instancetype)initWithInitWithConnectionHandler:(MXiAbstractConnectionHandler *)connectionHandler
                                    forDomainName:(NSString *)domainName
                                      andDelegate:(id <MXiMultiUserChatDiscoveryDelegate>)delegate
{
    NSAssert(connectionHandler != nil, @"Connection Handler reference must not be nil");
    NSAssert(domainName != nil && ![domainName isEqualToString:@""], @"Domain name must not be nil or empty");
    NSAssert(delegate != nil, @"Delegate must not be nil");
    
    self = [super init];
    if (self) {
        self.discoveredRooms = [NSMutableArray arrayWithCapacity:10];

        _delegate = delegate;
        _domainName = domainName;
        
        __connectionHandler = connectionHandler;
    }
    
    return self;
}

- (void)startDiscoveryWithResultQueue:(dispatch_queue_t)resultQueue;
{
    if (!resultQueue) _resultQueue = dispatch_get_main_queue();
    else _resultQueue = resultQueue;

    [__connectionHandler.connection addStanzaDelegate:self withSelector:@selector(didReceiveIQ:) forStanzaElement:IQ];

    XMPPIQ *iq = [self constructInformationQueryWithAddressee:_domainName
                                                    elementID:@"discoverDomainItems"
                                                    queryType:serviceDiscoItemsNS];
    [__connectionHandler sendElement:iq];
}

- (void)didReceiveIQ:(XMPPIQ *)xmppiq
{
    if ([xmppiq isResultIQ] && [[xmppiq attributeStringValueForName:@"id"] isEqualToString:@"discoverDomainItems"]) {
        [self domainItemsRequest:xmppiq];
    }
    if ([xmppiq isResultIQ] && [self isDiscoItemsResult:xmppiq]) {
        if ([self hasItemMUCFeature:xmppiq]) {
            XMPPIQ *iq = [self constructInformationQueryWithAddressee:[[xmppiq from] full]
                                                            elementID:@"roomDiscovery"
                                                            queryType:serviceDiscoItemsNS];
            [[MXiConnectionHandler sharedInstance] sendElement:iq];
        }
    }
    if ([xmppiq isResultIQ] && [[xmppiq attributeStringValueForName:@"id"] isEqualToString:@"roomDiscovery"]) {
        [self readRoomItemsFromIQ:xmppiq];
    }
}

#pragma mark - Build Queries

- (XMPPIQ *)constructInformationQueryWithAddressee:(NSString *)jid elementID:(NSString *)elementID queryType:(NSString *)queryType
{
    NSXMLElement *query = [[NSXMLElement alloc] initWithName:@"query" xmlns:queryType];
    XMPPIQ *iq = [[XMPPIQ alloc] initWithType:@"get" to:[XMPPJID jidWithString:jid] elementID:elementID child:query];

    return iq;
}

- (void)domainItemsRequest:(XMPPIQ *)xmppiq
{
    NSArray *items = [[xmppiq childElement] elementsForName:@"item"];
    self.cachedDomainItems = [NSMutableArray arrayWithCapacity:items.count];
    int index = 0;
    for (NSXMLElement *element in items) {
        NSString *jid = [element attributeStringValueForName:@"jid"];
        NSString *elementID = [NSString stringWithFormat:@"elementDisco_%i", index++];
        XMPPIQ *iq = [self constructInformationQueryWithAddressee:jid elementID:elementID queryType:serviceDiscoInfoNS];
        [__connectionHandler sendElement:iq];
        [self.cachedDomainItems addObject:jid];
    }
}

#pragma mark - Helper

- (BOOL)isDiscoItemsResult:(XMPPIQ *)xmppiq
{
    NSCharacterSet *characterSet = [NSCharacterSet characterSetWithCharactersInString:@"_"];
    NSString *idAttribute = [xmppiq attributeStringValueForName:@"id"];

    NSArray *idAttributeComponents = [idAttribute componentsSeparatedByCharactersInSet:characterSet];
    if (idAttributeComponents && idAttributeComponents.count >= 1)
        if ([((NSString *) idAttributeComponents[0]) isEqualToString:@"elementDisco"])
            return YES;

    return NO;
}

- (BOOL)hasItemMUCFeature:(XMPPIQ *)xmppiq
{
    NSArray *features = [[xmppiq childElement] elementsForName:@"feature"];
    BOOL isMUC = NO;
    for (NSXMLElement *element in features)
        if ([[element attributeStringValueForName:@"var"] isEqualToString:mucFeatureString]) {
            isMUC = YES;
            break;
        }

    return isMUC;
}

- (void)readRoomItemsFromIQ:(XMPPIQ *)xmppiq
{
    NSArray *rooms = [[xmppiq childElement] elementsForName:@"item"];
    @synchronized (self.discoveredRooms) {
        for (NSXMLElement *roomElement in rooms)
            [self.discoveredRooms addObject:[[MXiMultiUserChatRoom alloc] initWithName:[roomElement attributeStringValueForName:@"name"]
                                                                              jabberID:[XMPPJID jidWithString:[roomElement attributeStringValueForName:@"jid"]]]];
    }
    dispatch_async(_resultQueue, ^
    {
        if ([_delegate respondsToSelector:@selector(multiUserChatRoomsDiscovery:discoveredChatRooms:inDomain:)])
            [_delegate multiUserChatRoomsDiscovery:self
                               discoveredChatRooms:[NSArray arrayWithArray:self.discoveredRooms]
                                          inDomain:_domainName];
    });
}

#pragma mark - Equality

- (BOOL)isEqual:(id)object
{
    if (![object isKindOfClass:[self class]]) return NO;

    if ([object hash] == [self hash]) return YES;
    else return NO;
}

- (NSUInteger)hash
{
    return [_domainName hash];
}

@end