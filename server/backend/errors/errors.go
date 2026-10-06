package fberrors

import (
	"errors"
	"fmt"
)

var (
	ErrEmptyKey                 = errors.New("密钥为空")
	ErrExist                    = errors.New("resource already exists")
	ErrNotExist                 = errors.New("resource not found")
	ErrEmptyPassword            = errors.New("密码不能为空")
	ErrEasyPassword             = errors.New("密码强度不够，请使用更复杂的密码")
	ErrEmptyUsername            = errors.New("用户名不能为空")
	ErrEmptyRequest             = errors.New("请求为空")
	ErrScopeIsRelative          = errors.New("路径是相对路径")
	ErrInvalidDataType          = errors.New("数据类型无效")
	ErrIsDirectory              = errors.New("文件是目录")
	ErrInvalidOption            = errors.New("选项无效")
	ErrInvalidAuthMethod        = errors.New("认证方式无效")
	ErrPermissionDenied         = errors.New("权限不足")
	ErrInvalidRequestParams     = errors.New("请求参数无效")
	ErrSourceIsParent           = errors.New("源路径是父目录")
	ErrRootUserDeletion         = errors.New("不能删除唯一的管理员账户")
	ErrCurrentPasswordIncorrect = errors.New("当前密码不正确")
	ErrShareRequiresDownload    = errors.New("分享需要下载权限")
)

type ErrShortPassword struct {
	MinimumLength uint
}

func (e ErrShortPassword) Error() string {
	return fmt.Sprintf("密码太短，最少需要 %d 位", e.MinimumLength)
}
