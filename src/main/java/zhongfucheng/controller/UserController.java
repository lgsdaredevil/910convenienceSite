package zhongfucheng.controller;

import org.apache.commons.collections.map.HashedMap;
import org.apache.shiro.authc.IncorrectCredentialsException;
import org.apache.shiro.authc.UnknownAccountException;
import org.springframework.stereotype.Controller;
import org.springframework.validation.BindingResult;
import org.springframework.validation.ObjectError;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import zhongfucheng.entity.ActiveUser;
import zhongfucheng.entity.User;
import zhongfucheng.exception.UserException;
import zhongfucheng.utils.ReadPropertiesUtil;
import zhongfucheng.utils.vcode.Captcha;
import zhongfucheng.utils.vcode.GifCaptcha;

import javax.imageio.ImageIO;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.PrintWriter;
import java.util.*;
import java.util.List;

/**
 * Created by ozc on 2017/10/25.
 */

@Controller
@RequestMapping("/user")
public class UserController extends BaseController {

    /**
     * 先对数据进行校验，再注册
     *
     * @param user
     * @return
     * @throws Exception
     */
    @RequestMapping("/register.do")
    public String register(@Validated User user, BindingResult bindingResult) throws Exception {


        //如果参数不对，就直接返回注册页面
        List<ObjectError> allErrors = bindingResult.getAllErrors();
        if (allErrors != null && allErrors.size() > 0) {
            return "redirect:/goURL/user/toRegister.do";
        }


        //对密码进行加密md5(密码+salt)后才存到数据库中
        userService.encryptedPassword(user);

        userService.insert(user);

        //提示用户发送了邮件，让用户激活账户
        String url = getProjectPath() + "/user/activate.do?userId=" + user.getUserId();
        emailService.sendEmail(user, "注册", url);

        return "redirect:/common/countDown.html";
    }

    /**
     * 检测邮箱是否存在
     *
     * @param userEmail
     * @param writer
     * @throws Exception
     */
    @RequestMapping("/validateEmail.do")
    public void validateEmail(String userEmail, PrintWriter writer) throws Exception {
        User user = userService.validateEmailExist(userEmail);
        if (user != null && user.getUserId() != null) {
            writer.write("hasEmail");
        } else {
            writer.write("noEmail");
        }
    }

    /**
     * 激活账户（实际上就是修改表字段的值）
     *
     * @param userId
     * @return
     * @throws Exception
     */
    @RequestMapping("/activate.do")
    public String activate(String userId) throws Exception {

        User user = userService.selectByPrimaryKey(userId);
        String title = "";
        String content = "";
        String subject = "";


        if (user != null) {

            //得到当前时间和邮件时间对比,24小时内
            if (System.currentTimeMillis() - user.getTokenExptime().getTime() < 86400000) {
                user.setActiState(User.ACTIVATION_SUCCESSFUL);
                userService.updateByPrimaryKeySelective(user);
                title = "用户激活页面";
                subject = "用户激活";
                content = "恭喜您成功激活账户";
            } else {
                title = "激活失败页面";
                subject = "用户激活";
                content = "激活链接已超时，请重新注册";

                //删除记录已便用户再次注册
                userService.deleteByPrimaryKey(userId);

            }
        }

        //根据模版生成页面，重定向到页面中
        Map<String, Object> map = new HashedMap();
        map.put("title", title);
        map.put("content", content);
        map.put("subject", subject);
        map.put("path", getProjectPath());
        createCommonHtml("promptPages.ftl", "promptPages.html", map);

        return "redirect:/promptPages.html";
    }


    /**
     * 生成随机验证码
     *
     * @param request
     * @param response
     * @throws Exception
     * @deprecated 已被动态GIF验证码替代
     */
    @RequestMapping("/createCaptcha.do")
    public void createCaptcha(HttpServletRequest request, HttpServletResponse response) throws Exception {

        BufferedImage img = new BufferedImage(68, 22, 1);
        Graphics g = img.getGraphics();
        Random r = new Random();
        Color c = new Color(253, 255, 238);
        g.setColor(c);
        g.fillRect(0, 0, 68, 22);
        StringBuffer sb = new StringBuffer();
        char[] ch = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();
        int len = ch.length;

        for (int i = 0; i < 4; ++i) {
            int index = r.nextInt(len);
            g.setColor(new Color(r.nextInt(88), r.nextInt(188), r.nextInt(255)));
            g.setFont(new Font("Arial", 3, 22));
            g.drawString("" + ch[index], i * 15 + 3, 18);
            sb.append(ch[index]);
        }
        request.getSession().setAttribute("captcha", sb.toString());
        ImageIO.write(img, "JPG", response.getOutputStream());
    }

    /**
     * 获取验证码（Gif版本）
     *
     * @param response
     */
    @RequestMapping(value = "/getGifCode", method = RequestMethod.GET)
    public void getGifCode(HttpServletResponse response, HttpServletRequest request) throws IOException {

        response.setHeader("Pragma", "No-cache");
        response.setHeader("Cache-Control", "no-cache");
        response.setDateHeader("Expires", 0);
        response.setContentType("image/gif");
        /**
         * gif格式动画验证码
         * 宽，高，位数。
         */
        Captcha captcha = new GifCaptcha(146, 42, 4);



        /**
         * 把验证码写到浏览器后才能知道验证码的数据，才能把数据装到session中，在后台会报出异常，我认为这样设计得不好。虽然不影响使用
         * 作者：ozc
         */
        ServletOutputStream out = response.getOutputStream();
        captcha.out(out);
        request.getSession().setAttribute("captcha", captcha.text().toLowerCase());



    }


    @RequestMapping("/login.do")
    public String login(HttpServletRequest request, HttpServletResponse response) throws Exception {

        /**
         * 如果在shiro配置文件中配置了authc的话，那么所点击的url就需要认证了才可以访问
         * a：如果url是登陆请求地址(user/login.do)，不是post请求的话，流程是不会去Realm中的。那么会返回到该方法中，也就是会返回登陆页面
         * b：如果url是登陆页面地址，是post请求的话，那么去realm中对比，如果成功了那么跳转到在表单过滤器中配置的url中
         *
         * c：如果url不是登陆页面地址，那么表单过滤器会重定向到此方法中，该方法返回登陆页面地址。并记住是哪个url被拦截住了
         * d：用户填写完表单之后，会进入b环节，此时登陆成功后跳转的页面是c环节记住的url。
         */

        //如果登陆失败从request中获取认证异常信息，shiroLoginFailure就是shiro异常类的全限定名
        String exceptionClassName = (String) request.getAttribute("shiroLoginFailure");

        //根据shiro返回的异常类路径判断，抛出指定异常信息
        if (exceptionClassName != null) {
            if (UnknownAccountException.class.getName().equals(exceptionClassName)) {

                throw new UserException("账号不存在");
            } else if (IncorrectCredentialsException.class.getName().equals(
                    exceptionClassName)) {
                throw new UserException("密码错误了");
            } else if ("captchaCodeError".equals(exceptionClassName)) {
                throw new UserException("验证码错误了");
            } else {
                throw new Exception();//最终在异常处理器生成未知错误
            }
        }
        return "redirect:/goURL/user/toLogin.do";
    }

    /**
     * 得到用户的身份信息，返回JSON
     *
     * @param session
     * @return
     */
    @RequestMapping("/getUser.do")
    @ResponseBody
    public Map<String, Object> getUser(HttpSession session) {

        Map<String, Object> resultMap = new LinkedHashMap<String, Object>();
        ActiveUser user = (ActiveUser) session.getAttribute("activeUser");
        if (user != null) {
            resultMap.put("user", user);
        }
        return resultMap;
    }


    /**
     * 用户忘记密码，发送邮件让用户去修改密码
     *
     * @param userEmail
     * @return
     * @throws Exception
     */
    @RequestMapping("/forgetPassword.do")
    public String forgetPassword(String userEmail) throws Exception {
        //判断是否有该用户
        User user = null;
        if (userEmail != null) {
            user = userService.validateUserExist(userEmail);
        }
        //提示用户发送了邮件，让用户重新设置密码账户
        String url = ReadPropertiesUtil.readProp("projectPath") + "/common/resetView.html?userId=" + user.getUserId();
        emailService.sendEmail(user, "重置密码", url);

        //设置邮件发送时间、30分钟链接失效
        user.setTokenExptime(new Date());
        userService.updateByPrimaryKeySelective(user);

        return "redirect:/common/countDown.html";
    }

    /**
     * 修改密码
     *
     * @param user
     * @return
     */
    @RequestMapping("/resetPassword.do")
    @ResponseBody
    public Map<String, Object> resetPassword(User user) {

        Map<String, Object> resultMap = new LinkedHashMap<String, Object>();

        if (user != null && user.getUserId() != null) {
            User user1 = userService.selectByPrimaryKey(user.getUserId());

            //得到当前时间和邮件时间对比,24小时
            if (System.currentTimeMillis() - user1.getTokenExptime().getTime() < 86400000) {
                userService.encryptedPassword(user);
                int num = userService.updateByPrimaryKeySelective(user1);
                if (num > 0) {
                    resultMap.put("message", "修改成功");
                } else {
                    resultMap.put("message", "修改失败");
                }
                return resultMap;
            } else {
                resultMap.put("message", "链接已超时，请重新进行操作");

            }

        }

        return null;
    }



}
