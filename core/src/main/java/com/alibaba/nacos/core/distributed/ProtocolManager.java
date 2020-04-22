/*
 * Copyright 1999-2018 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.nacos.core.distributed;

import com.alibaba.nacos.consistency.Config;
import com.alibaba.nacos.consistency.ConsistencyProtocol;
import com.alibaba.nacos.consistency.LogProcessor;
import com.alibaba.nacos.consistency.ap.APProtocol;
import com.alibaba.nacos.consistency.ap.LogProcessor4AP;
import com.alibaba.nacos.consistency.cp.CPProtocol;
import com.alibaba.nacos.consistency.cp.LogProcessor4CP;
import com.alibaba.nacos.core.cluster.Member;
import com.alibaba.nacos.core.cluster.MemberChangeEvent;
import com.alibaba.nacos.core.cluster.MemberChangeListener;
import com.alibaba.nacos.core.cluster.MemberMetaDataConstants;
import com.alibaba.nacos.core.cluster.ServerMemberManager;
import com.alibaba.nacos.core.notify.NotifyCenter;
import com.alibaba.nacos.core.utils.ApplicationUtils;
import com.alibaba.nacos.core.utils.ClassUtils;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.event.ContextStartedEvent;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Conformance protocol management, responsible for managing the lifecycle
 * of conformance protocols in Nacos
 *
 * @author <a href="mailto:liaochuntao@live.com">liaochuntao</a>
 */
@SuppressWarnings("all")
@Component(value = "ProtocolManager")
@DependsOn("serverMemberManager")
public class ProtocolManager
		implements ApplicationListener<ContextStartedEvent>, DisposableBean,
		MemberChangeListener {

	private CPProtocol cpProtocol;
	private APProtocol apProtocol;

	@Autowired
	private ServerMemberManager memberManager;

	@PostConstruct
	public void init() {

		this.memberManager = memberManager;
		NotifyCenter.registerSubscribe(this);

		// Consistency protocol module initialization

		initAPProtocol();
		initCPProtocol();
	}

	public void destroy() {
		if (Objects.nonNull(apProtocol)) {
			apProtocol.shutdown();
		}
		if (Objects.nonNull(cpProtocol)) {
			cpProtocol.shutdown();
		}
	}

	@Override
	public void onApplicationEvent(ContextStartedEvent event) {
		stopDeferPublish();
	}

	public void stopDeferPublish() {
		if (Objects.nonNull(apProtocol)) {
			apProtocol.protocolMetaData().stopDeferPublish();
		}
		if (Objects.nonNull(cpProtocol)) {
			cpProtocol.protocolMetaData().stopDeferPublish();
		}
	}

	private void initAPProtocol() {
		ApplicationUtils.getBeanIfExist(APProtocol.class, protocol -> {
			Class configType = ClassUtils.resolveGenericType(protocol.getClass());
			Config config = (Config) ApplicationUtils.getBean(configType);
			injectMembers4AP(config);
			config.addLogProcessors(loadProcessor(LogProcessor4AP.class, protocol));
			protocol.init((config));
			ProtocolManager.this.apProtocol = protocol;
		});
	}

	private void initCPProtocol() {
		ApplicationUtils.getBeanIfExist(CPProtocol.class, protocol -> {
			Class configType = ClassUtils.resolveGenericType(protocol.getClass());
			Config config = (Config) ApplicationUtils.getBean(configType);
			injectMembers4CP(config);
			config.addLogProcessors(loadProcessor(LogProcessor4CP.class, protocol));
			protocol.init((config));
			ProtocolManager.this.cpProtocol = protocol;
		});
	}

	private void injectMembers4CP(Config config) {
		final Member selfMember = memberManager.getSelf();
		final String self = selfMember.getIp() + ":" + Integer.parseInt(String.valueOf(
				selfMember.getExtendVal(MemberMetaDataConstants.RAFT_PORT)));
		Set<String> others = toCPMembersInfo(memberManager.allMembers());
		config.setMembers(self, others);
	}

	private void injectMembers4AP(Config config) {
		final String self = memberManager.getSelf().getAddress();
		Set<String> others = toAPMembersInfo(memberManager.allMembers());
		config.setMembers(self, others);
	}

	@SuppressWarnings("all")
	private List<LogProcessor> loadProcessor(Class cls, ConsistencyProtocol protocol) {
		final Map<String, LogProcessor> beans = (Map<String, LogProcessor>) ApplicationUtils
				.getBeansOfType(cls);
		final List<LogProcessor> result = new ArrayList<>(beans.values());
		return result;
	}

	@Override
	public void onEvent(MemberChangeEvent event) {
		// Here, the sequence of node change events is very important. For example,
		// node change event A occurs at time T1, and node change event B occurs at
		// time T2 after a period of time.
		// (T1 < T2)

		Set<Member> copy = new HashSet<>(event.getAllMembers());

		// Node change events between different protocols should not block each other
		if (Objects.nonNull(apProtocol)) {
			ProtocolExecutor.apMemberChange(() -> apProtocol.memberChange(toAPMembersInfo(copy)));
		}
		if (Objects.nonNull(cpProtocol)) {
			ProtocolExecutor.cpMemberChange(() -> cpProtocol.memberChange(toCPMembersInfo(copy)));
		}
	}

	@Override
	public boolean ignoreExpireEvent() {
		return true;
	}

	private static Set<String> toAPMembersInfo(Collection<Member> members) {
		Set<String> nodes = new HashSet<>();
		members.forEach(member -> nodes.add(member.getAddress()));
		return nodes;
	}

	private static Set<String> toCPMembersInfo(Collection<Member> members) {
		Set<String> nodes = new HashSet<>();
		members.forEach(member -> {
			final String ip = member.getIp();
			final int port = Integer.parseInt(String.valueOf(
					member.getExtendVal(MemberMetaDataConstants.RAFT_PORT)));
			nodes.add(ip + ":" + port);
		});
		return nodes;
	}
}
