package kr.teamagent.chat.web;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.util.FileCopyUtils;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.ModelAndView;

import egovframework.com.cmm.util.EgovResourceCloseHelper;
import kr.teamagent.chat.service.ChatbotService;
import kr.teamagent.chat.service.ChatbotVO;
import kr.teamagent.common.util.PropertyUtil;
import kr.teamagent.common.util.SessionUtil;
import kr.teamagent.common.web.BaseController;

@Controller
public class ChatbotController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(ChatbotController.class);

    @Autowired
    private ChatbotService chatbotService;

    @RequestMapping(value="/ai/chatbot/stat/selectAiDailyUsage.do")
    @ResponseBody
    public ModelAndView selectAiDailyUsage(ChatbotVO searchVO)throws Exception {
        HashMap<String, Object> resultMap = new HashMap<>();
        searchVO.setUserId(SessionUtil.getUserId());
        resultMap.put("data", chatbotService.selectAiDailyUsage(searchVO));
        return new ModelAndView("jsonView", resultMap);
    }



    /**
     * 챗봇 매뉴얼 PDF 파일 조회
     * - 로컬 환경: 파일 시스템에서 조회
     * - 개발/운영 환경: S3에서 조회
     * 
     * AI 서버에서 받은 file_path 예시: chatbot/manual/CMB EPR시스템 메뉴얼(전체메뉴).pdf
     * 
     * @param dataVO filePath 필드에 파일 경로가 포함됨
     * @param response
     * @throws Exception
     */
    @RequestMapping(value = "/ai/chatbot/manual/fileView.do")
    @ResponseBody
    public void manualFileView(ChatbotVO dataVO, HttpServletResponse response) throws Exception {
        String filePath = dataVO.getFilePath();
        
        if (filePath == null || filePath.isEmpty()) {
            log.warn("filePath is null or empty");
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }

        // 환경 구분: 로컬은 파일 시스템, 개발/운영은 S3 사용
        String env = PropertyUtil.getProperty("Globals.env");
        boolean isLocal = "local".equals(env);
        
        if (isLocal) {
            // 로컬 환경: 파일 시스템에서 조회
            serveFileFromLocal(filePath, response);
        } else {
            // 개발/운영 환경: S3에서 조회
            // serveFileFromS3(filePath, response);
        }
    }

    /**
     * 로컬 파일 시스템에서 파일 조회
     */
    private void serveFileFromLocal(String filePath, HttpServletResponse response) throws Exception {
        String fileStorePath = PropertyUtil.getProperty("Globals.fileStorePath");
        if (fileStorePath == null || fileStorePath.isEmpty()) {
            log.error("Globals.fileStorePath is not configured");
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return;
        }

        if (!fileStorePath.endsWith("/") && !fileStorePath.endsWith(File.separator)) {
            fileStorePath += File.separator;
        }

        if (filePath.startsWith("/") || filePath.startsWith(File.separator)) {
            filePath = filePath.substring(1);
        }

        File uFile = new File(fileStorePath + filePath);
        if (!uFile.exists() || !uFile.isFile()) {
            log.warn("File not found in local filesystem: {}", uFile.getAbsolutePath());
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        response.setContentType("application/pdf");
        response.setContentLengthLong(uFile.length());
        response.setHeader("Content-Disposition", "inline; filename=\"manual.pdf\"");

        BufferedInputStream in = null;
        BufferedOutputStream out = null;
        try {
            in = new BufferedInputStream(new FileInputStream(uFile));
            out = new BufferedOutputStream(response.getOutputStream());
            FileCopyUtils.copy(in, out);
            out.flush();
            
            log.info("Manual file served successfully from local filesystem: {}", uFile.getAbsolutePath());
        } finally {
            EgovResourceCloseHelper.close(in, out);
        }
    }

    @RequestMapping("/ai/chatbot/stat/saveSatisYn.do")
    public @ResponseBody Map<String, Object> saveSatisYn(@RequestBody ChatbotVO dataVO, BindingResult bindingResult) throws Exception {
        Map<String, Object> resultMap = new HashMap<>();

        try {
            // 유효성 검사
            if (bindingResult.hasErrors()) {
                resultMap.put("successYn", false);
                resultMap.put("returnMsg", "요청사항을 실패하였습니다.");
                return resultMap;
            }

            resultMap = chatbotService.saveSatisYn(dataVO);

        } catch (Exception e) {
            resultMap.put("successYn", false);
            resultMap.put("returnMsg", "요청사항을 실패하였습니다. (" + e.getMessage() + ")");
        }

        return resultMap;
    }

    
}
