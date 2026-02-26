/*
 * Copyright 2008-2009 MOPAS(MINISTRY OF SECURITY AND PUBLIC ADMINISTRATION).
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
package egovframework.com.cmm.filter;

import lombok.SneakyThrows;
import org.codehaus.jackson.map.ObjectMapper;

import javax.servlet.ServletOutputStream;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.util.Map;

/**
*
* HTMLTagFilterRequestWrapper
* @author 공통컴포넌트 팀 신용호
* @since 2018.03.21
* @version 1.0
* @see
*
* <pre>
* << 개정이력(Modification Information) >>
*
*   수정일              수정자              수정내용
*  -------      --------    ---------------------------
*   2018.03.21  신용호              getParameterMap()구현 추가
*   2019.01.31  신용호              whiteList 태그 추가
*
*/

public class DescryptResponseWrapper extends HttpServletResponseWrapper {

	ServletOutputStream outStream;

	public DescryptResponseWrapper(HttpServletResponse response) {
		super(response);
	}

	public ServletOutputStream getOutputStream() throws IOException {
		outStream = super.getOutputStream();
		return outStream;
    }
}