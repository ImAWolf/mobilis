//
//  MXiServiceManager.h
//  MXi
//
//  Created by Martin Weißbach on 11/8/13.
//  Copyright (c) 2013 TU Dresden. All rights reserved.
//

#import <Foundation/Foundation.h>

#import "MXiDefinitions.h"

@class MXiConnection;
@protocol MXiServiceManagerDelegate;
@class MXiService;

/*!
    @class MXiServiceManager

    Service instances for MULTI-mode and SINGLE-mode services are stored, manged and discovered by this class.
 */
@interface MXiServiceManager : NSObject

/// List of MXiServices that were discovered for the service. A service discovery must complete without errors before this list contains any objects.
@property (atomic, readonly) NSArray *services;

@property (nonatomic, readonly, weak) MXiConnection *connection;
/// Service type used by the manager.
@property (nonatomic, readonly) ServiceType serviceType;
/// The unique namespace of services and service instances managed by MXiServiceManager.
@property (nonatomic, readonly) NSString *namespace;

/*!
    Returns a new instance of the MXiServiceManager. A new service discovery for the specified namespace will be launched
    automatically before the method returns.
    You should not call this method directly but rather use the reference held by the MXiConnectionHandler.
    If you need to be informed on events, add yourself as a delegate.

    @param connection       The MXiConnection to use for service discovery.
    @param serviceType      The type of the service for which instances should be managed.
    @param namespace        The namespace to search for while service discovery. Common identifier for all instances.
    @param delegate         Set a delegate initially so that another object is being informed when service discovery finishes.

    @return An instance of MXiServerManager.

    @see MXiConnection
    @see MXiConnectionHandler
    @see MXiServiceManagerDelegate
    @see ServiceType
 */
+ (instancetype)serviceManagerWithConnection:(MXiConnection *)connection serviceType:(ServiceType)serviceType namespace:(NSString *)namespace delegate:(id <MXiServiceManagerDelegate>)delegate;

/*!
    Relaunches the service discovery with the given configuration of the receiver.
    Use this method to synchronize the list of services with the XMPP Mobilis runtime, meaning fetch the service list from the
    Mobilis runtime.

    @discussion This step is not necessary, if the ServiceManager is created. Service discovery will be launched automatically on creation.
 */
- (void)rediscoverServices;

- (void)addDelegate:(id <MXiServiceManagerDelegate>)delegate;
- (void)removeDelegate:(id<MXiServiceManagerDelegate>)delegate;

/*!
    Launch the creation of a new Multi-Service-Instance.

    @param  serviceName     The display name of the new service instance.
    @param  password        The password to limit access to the new service instance.
 */
- (void)createServiceWithName:(NSString *)serviceName andPassword:(NSString *)password;

@end

/*!
    @protocol MXiServiceManagerDelegate

    A protocol declaring a common interface objects acting as delegates of the `MXiServiceManager` class should implement.
 */
@protocol MXiServiceManagerDelegate <NSObject>

- (void)serviceDiscoveryFinishedWithError:(NSError *)error;

@optional

- (void)createdServiceInstanceSuccessfully:(MXiService *)service;

@end