package com.travel.agent.service;

import com.travel.agent.domain.Itinerary;

/**
 * 行程状态存储接口（E1）：存储策略可替换——内存（开发）/Redis（多实例生产）。
 * 接口存在本身就是一个设计声明：会话服务不关心状态放哪，只关心存取语义。
 */
public interface ItineraryStore {

    void save(String cid, Itinerary itinerary);

    Itinerary get(String cid);
}
