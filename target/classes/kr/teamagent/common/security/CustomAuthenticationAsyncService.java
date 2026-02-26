/*
package kr.teamagent.common.security;

import kr.teamagent.common.security.service.AccessLoginVO;
import kr.teamagent.common.security.service.UserVO;
import kr.teamagent.common.security.service.impl.LoginServiceImpl;
import lombok.SneakyThrows;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;


@Service
class CustomAuthenticationAsyncService {

	public final Logger logger = LoggerFactory.getLogger(this.getClass());

	@Autowired
	LoginServiceImpl loginService;

	@Autowired
  	@Qualifier("CustomAuthenticationAsyncConfig")
  	private ThreadPoolTaskExecutor executor;

	public Integer selectAuthStatusCount(UserVO userVO) throws Exception {
        // 비동기 실행 로직 작성
		Callable c = new Callable() {
			@Override
			public Object call() throws Exception {
				logger.info("Thread.currentThread().getName() ->{}", Thread.currentThread().getName());
				return loginService.selectAuthStatusCount(userVO);
			}
		};


	  	Future<Integer> f = executor.submit(c);
      	return f.get();

    }

	public UserVO selectUser(UserVO userVO) throws Exception {
        // 비동기 실행 로직 작성
		Callable c = new Callable() {
			@Override
			public Object call() throws Exception {
				logger.info("Thread.currentThread().getName() ->{}", Thread.currentThread().getName());
				return loginService.selectUser(userVO);
			}
		};
	  	Future<UserVO> f = executor.submit(c);
		return f.get();
    }

	public List<String> selectAdminGubunList(UserVO userVO) throws Exception {
        // 비동기 실행 로직 작성
		//List<String> list = new ArrayList<>();
		Callable c = new Callable() {
			@Override
			public Object call() throws Exception {
				logger.info("Thread.currentThread().getName() ->{}", Thread.currentThread().getName());
				return loginService.selectAdminGubunList(userVO);
			}
		};

	  Future<List<String>> f = executor.submit(c);

      return f.get();
    }

	public void insertAccessCertificationFailData(AccessLoginVO accessLoginVO) {
		// 비동기 실행 로직 작성
		Runnable r = new Runnable() {
			@SneakyThrows
			@Override
			public void run() {
				logger.info("Thread.currentThread().getName() ->{}", Thread.currentThread().getName());
				loginService.insertAccessCertificationFailData(accessLoginVO);
			}
		};
		executor.execute(r);

	}

	public void insertAccessCertificationSuccessData(AccessLoginVO accessLoginVO) {
		// 비동기 실행 로직 작성
		Runnable r = new Runnable() {
			@SneakyThrows
			@Override
			public void run() {
				//System.out.println("Thread.currentThread().getName()  : " + Thread.currentThread().getName() );
				loginService.insertAccessCertificationSuccessData(accessLoginVO);
			}
		};

		executor.execute(r);
	}
}
*/
