/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.cloud.network.guru;

import com.cloud.dc.DataCenter;
import com.cloud.dc.Vlan;
import com.cloud.deploy.DeployDestination;
import com.cloud.deploy.DeploymentPlan;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientAddressCapacityException;
import com.cloud.exception.InsufficientVirtualNetworkCapcityException;
import com.cloud.network.*;
import com.cloud.network.Network;
import com.cloud.network.addr.PublicIp;
import com.cloud.offering.NetworkOffering;
import com.cloud.user.Account;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.net.NetUtils;
import com.cloud.user.AccountVO;
import com.cloud.user.dao.AccountDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.vm.*;
import org.apache.log4j.Logger;
import java.net.URI;

import javax.ejb.Local;
import javax.inject.Inject;

@Local(value = NetworkGuru.class)
public class MidoNetPublicNetworkGuru extends PublicNetworkGuru {
    private static final Logger s_logger = Logger.getLogger(MidoNetPublicNetworkGuru.class);

    // Inject any stuff we need to use (DAOs etc)
    @Inject
    NetworkModel _networkModel;
    @Inject
    AccountDao _accountDao;

    // Don't need to change traffic type stuff, public is fine

    // Only change is to make broadcast domain type Mido
    @Override
    public Network design(NetworkOffering offering, DeploymentPlan plan, Network network, Account owner) {
        s_logger.debug("design called with network: " + network.toString());
        if (!canHandle(offering)) {
            return null;
        }

        if (offering.getTrafficType() == Networks.TrafficType.Public) {
            NetworkVO ntwk = new NetworkVO(offering.getTrafficType(), Networks.Mode.Static, Networks.BroadcastDomainType.Mido,
                    offering.getId(), Network.State.Allocated, plan.getDataCenterId(), plan.getPhysicalNetworkId());
            return ntwk;
        } else {
            return null;
        }
    }

    protected MidoNetPublicNetworkGuru() {
        super();
    }

    protected void getIp(NicProfile nic, DataCenter dc, VirtualMachineProfile<? extends VirtualMachine> vm, Network network) throws InsufficientVirtualNetworkCapcityException,
            InsufficientAddressCapacityException, ConcurrentOperationException {
        if (nic.getIp4Address() == null) {
            PublicIp ip = _networkMgr.assignPublicIpAddress(dc.getId(), null, vm.getOwner(), Vlan.VlanType.VirtualNetwork, null, null, false);
            nic.setIp4Address(ip.getAddress().toString());

            nic.setGateway(ip.getGateway());

            // Set netmask to /24 for now
            // TDO make it /32 and go via router for anything else on the subnet
            nic.setNetmask("255.255.255.0");

            // Make it the default nic so that a default route is set up.
            nic.setDefaultNic(true);

            //nic.setIsolationUri(Networks.IsolationType..Mido.toUri(ip.getVlanTag()));
            nic.setBroadcastUri(network.getBroadcastUri());
            //nic.setBroadcastType(Networks.BroadcastDomainType.Vlan);
            nic.setFormat(Networks.AddressFormat.Ip4);
            nic.setReservationId(String.valueOf(ip.getVlanTag()));
            nic.setMacAddress(ip.getMacAddress());
        }

        nic.setDns1(dc.getDns1());
        nic.setDns2(dc.getDns2());
    }

    @Override
    public void updateNicProfile(NicProfile profile, Network network) {
        s_logger.debug("updateNicProfile called with network: " + network.toString() + " profile: " + profile.toString());

        DataCenter dc = _dcDao.findById(network.getDataCenterId());
        if (profile != null) {
            profile.setDns1(dc.getDns1());
            profile.setDns2(dc.getDns2());
        }
    }

    @Override
    public NicProfile allocate(Network network, NicProfile nic, VirtualMachineProfile<? extends VirtualMachine> vm)
            throws InsufficientVirtualNetworkCapcityException,
            InsufficientAddressCapacityException, ConcurrentOperationException {

        s_logger.debug("allocate called with network: " + network.toString() + " nic: " + nic.toString() + " vm: " + vm.toString());
        DataCenter dc = _dcDao.findById(network.getDataCenterId());

        if (nic != null && nic.getRequestedIpv4() != null) {
            throw new CloudRuntimeException("Does not support custom ip allocation at this time: " + nic);
        }

        if (nic == null) {
            nic = new NicProfile(Nic.ReservationStrategy.Create, null, null, null, null);
        }

        getIp(nic, dc, vm, network);

        if (nic.getIp4Address() == null) {
            nic.setStrategy(Nic.ReservationStrategy.Start);
        } else if (vm.getVirtualMachine().getType() == VirtualMachine.Type.DomainRouter){
            nic.setStrategy(Nic.ReservationStrategy.Managed);
        } else {
            nic.setStrategy(Nic.ReservationStrategy.Create);
        }

        nic.setBroadcastUri(generateBroadcastUri(network));

        return nic;
    }

    @Override
    public void reserve(NicProfile nic, Network network, VirtualMachineProfile<? extends VirtualMachine> vm, DeployDestination dest, ReservationContext context)
            throws InsufficientVirtualNetworkCapcityException, InsufficientAddressCapacityException, ConcurrentOperationException {
        s_logger.debug("reserve called with network: " + network.toString() + " nic: " + nic.toString() + " vm: " + vm.toString());
        if (nic.getIp4Address() == null) {
            getIp(nic, dest.getDataCenter(), vm, network);
        }
    }

    @Override
    public boolean release(NicProfile nic, VirtualMachineProfile<? extends VirtualMachine> vm, String reservationId) {
        s_logger.debug("release called with nic: " + nic.toString() + " vm: " + vm.toString());
        return true;
    }

    @Override
    public Network implement(Network network, NetworkOffering offering, DeployDestination destination, ReservationContext context)
            throws InsufficientVirtualNetworkCapcityException {
        s_logger.debug("implement called with network: " + network.toString());
        long dcId = destination.getDataCenter().getId();

        //get physical network id
        long physicalNetworkId = _networkModel.findPhysicalNetworkId(dcId, offering.getTags(), offering.getTrafficType());

        NetworkVO implemented = new NetworkVO(network.getTrafficType(), network.getMode(), network.getBroadcastDomainType(), network.getNetworkOfferingId(), Network.State.Allocated,
                network.getDataCenterId(), physicalNetworkId);

        if (network.getGateway() != null) {
            implemented.setGateway(network.getGateway());
        }

        if (network.getCidr() != null) {
            implemented.setCidr(network.getCidr());
        }

        implemented.setBroadcastUri(generateBroadcastUri(network));

        return implemented;

    }

    @Override @DB
    public void deallocate(Network network, NicProfile nic, VirtualMachineProfile<? extends VirtualMachine> vm) {
        s_logger.debug("deallocate called with network: " + network.toString() + " nic: " + nic.toString() + " vm: " + vm.toString());
        if (s_logger.isDebugEnabled()) {
            s_logger.debug("public network deallocate network: networkId: " + nic.getNetworkId() + ", ip: " + nic.getIp4Address());
        }

        IPAddressVO ip = _ipAddressDao.findByIpAndSourceNetworkId(nic.getNetworkId(), nic.getIp4Address());
        if (ip != null && nic.getReservationStrategy() != Nic.ReservationStrategy.Managed) {

            Transaction txn = Transaction.currentTxn();
            txn.start();

            _networkMgr.markIpAsUnavailable(ip.getId());
            _ipAddressDao.unassignIpAddress(ip.getId());

            txn.commit();
        }
        nic.deallocate();

        if (s_logger.isDebugEnabled()) {
            s_logger.debug("Deallocated nic: " + nic);
        }
    }

    @Override
    public void shutdown(NetworkProfile network, NetworkOffering offering) {
        s_logger.debug("shutdown called with network: " + network.toString());
    }

    @Override
    public boolean trash(Network network, NetworkOffering offering, Account owner) {
        s_logger.debug("trash called with network: " + network.toString());
        return true;
    }

    @Override
    public void updateNetworkProfile(NetworkProfile networkProfile) {
        DataCenter dc = _dcDao.findById(networkProfile.getDataCenterId());
        networkProfile.setDns1(dc.getDns1());
        networkProfile.setDns2(dc.getDns2());
    }

    private URI generateBroadcastUri(Network network){
        AccountVO acc = _accountDao.findById(network.getAccountId());
        String accountUUIDStr = acc.getUuid();
        String networkUUIDStr = String.valueOf(network.getId());
        return Networks.BroadcastDomainType.Mido.toUri(accountUUIDStr +
                                                       "." +
                                                       networkUUIDStr +
                                                       ":" +
                                                       networkUUIDStr);
    }


}
