package kr.teamagent.common.web;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import kr.teamagent.common.system.service.impl.FileServiceImpl;
import kr.teamagent.common.util.service.FileVO;

@RestController
public class FileController extends BaseController {

    @Autowired
    private FileServiceImpl fileService;

    @PostMapping("/com/file/uploadFile.do")
    public Map<String, Object> uploadFile(@RequestBody FileVO req) {
        return fileService.createUploadPresignedUrl(req);
    }

    @RequestMapping("/com/file/viewFile.do")
    public Map<String, Object> viewFile(@RequestBody FileVO dataVO) throws Exception {
        return fileService.createViewPresignedUrl(dataVO);
    }

    @RequestMapping("/com/file/downloadFile.do")
    public Map<String, Object> downloadFile(@RequestBody FileVO dataVO) throws Exception {
        return fileService.createDownloadPresignedUrl(dataVO);
    }

    @RequestMapping("/com/file/deleteFile.do")
    public @ResponseBody Map<String, Object> deleteFile(@RequestBody FileVO dataVO) throws Exception {
        return fileService.deleteFilesByDocIds(dataVO);
    }

}