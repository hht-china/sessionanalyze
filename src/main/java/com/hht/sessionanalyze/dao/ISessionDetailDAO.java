package com.hht.sessionanalyze.dao;

import com.hht.sessionanalyze.domain.SessionDetail;

import java.util.List;

/**
 * Session明细DAO接口
 * @author Administrator
 *
 */
public interface ISessionDetailDAO {

	/**
	 * 插入一条session明细数据
	 * @param sessionDetail 
	 */
	void insert(SessionDetail sessionDetail);
	
	/**
	 * 批量插入session明细数据
	 * @param sessionDetails
	 */
	void insertBatch(List<SessionDetail> sessionDetails);
	
}
