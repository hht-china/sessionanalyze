package com.hht.sessionanalyze.dao;

import com.hht.sessionanalyze.domain.Top10Session;

/**
 * top10活跃session的DAO接口
 * @author Administrator
 *
 */
public interface ITop10SessionDAO {

	void insert(Top10Session top10Session);
	
}
